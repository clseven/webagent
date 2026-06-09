# Agent 工程化稳定性整改方案

> 日期：2026-06-08  
> 范围：暂不包含原生 Tool Calling、工具并行、子智能体架构和前端大规模重构。  
> 目标：先稳定现有 Agent + 沙箱 + RAG + SSE 核心链路，降低并发、上下文和可维护性风险。

## 1. 背景

当前项目已经具备完整主链路：

```text
创建会话
  -> 创建/复用沙箱
  -> 用户聊天
  -> PlanAgent 规划
  -> KnowledgeEnhancer 做 RAG 增强
  -> ReactAgent 执行工具循环
  -> 工具访问沙箱/知识库
  -> 保存消息和 token 用量
  -> 返回普通响应或 SSE 事件流
```

代码能力已经不是玩具 demo，但工程化风险集中在几个点：

- `@Async` 自调用可能不生效。
- 多处 `CompletableFuture.supplyAsync()` 使用默认 `ForkJoinPool.commonPool()`。
- `UserContext` 和 `KnowledgeSearchTool` 的 `ThreadLocal` 在异步、流式和工具线程中可能丢失。
- `AgentServiceImpl` 同步和流式链路重复较多。
- RAG 检索存在 N+1 查询、Milvus 状态检查不足、rerank 输出不稳定等问题。
- SSE 链路存在手动 `subscribe()`、手动线程心跳、取消与资源释放不够清晰的问题。

本方案优先处理这些问题，不改变模型工具协议。

## 2. 非目标

本阶段明确不做：

- 不改原生 Tool Calling 协议。
- 不实现并行工具调用。
- 不引入子智能体架构。
- 不重做前端 UI。
- 不替换 LLM 供应商。
- 不重写 RAG 算法，只做可靠性和性能修正。

## 3. 推荐实施顺序

```text
独立异步 Bean
  -> 专用线程池
  -> 显式上下文
  -> AgentServiceImpl 拆分
  -> RAG 性能与状态检查
  -> SSE Reactor 化
  -> 自动化测试与可观测性
```

这个顺序的原则是：先修运行时边界，再重构编排层，最后优化具体功能链路。

## 4. 阶段一：修复异步边界

### 问题

`KnowledgeServiceImpl.upload()` 直接调用同类方法：

```java
processDocumentAsync(document.getId(), storagePath, splitMode, chunkSize, overlap);
```

如果 `processDocumentAsync()` 依赖 Spring `@Async`，同类内部调用不会经过 Spring 代理，异步通常不会生效。结果可能是上传请求同步等待文档解析、切片、embedding 和 Milvus 写入。

### 方案

新增独立 Bean：

```java
@Service
public class KnowledgeDocumentProcessor {
    @Async("documentExecutor")
    public void processDocumentAsync(...) {
        ...
    }
}
```

`KnowledgeServiceImpl` 只负责：

- 校验知识库。
- 保存文档元数据。
- 保存原始文件。
- 调用 `KnowledgeDocumentProcessor.processDocumentAsync(...)`。
- 立即返回文档记录。

### 验收标准

- 文档上传接口能快速返回。
- 日志显示文档处理运行在 `documentExecutor-*` 线程。
- 文档状态仍按 `PENDING -> PROCESSING -> READY/FAILED` 流转。
- 异步异常能落到文档 `FAILED` 状态并写入错误信息。

## 5. 阶段二：统一线程池

### 问题

项目中多个地方使用无 executor 的异步调用：

```java
CompletableFuture.supplyAsync(...)
CompletableFuture.runAsync(...)
```

默认会使用 JVM 公共线程池。沙箱命令、文件 IO、LLM 调用、RAG 检索都可能占用公共池，造成不可控阻塞。

### 方案

新增线程池配置，例如 `AsyncExecutorConfig`：

- `agentExecutor`：会话创建、Agent 后台任务。
- `toolExecutor`：沙箱工具执行。
- `ragExecutor`：多 query、多知识库检索。
- `documentExecutor`：文档解析、切片、embedding、Milvus 写入。

基础要求：

- 线程名前缀清晰。
- 配置 core/max/queue。
- 有统一异常处理。
- 拒绝策略明确，建议初期使用 `CallerRunsPolicy` 或业务可感知异常。

### 验收标准

- 项目内核心异步调用不再使用默认 commonPool。
- 日志能通过线程名前缀判断任务类型。
- 线程池饱和时行为可预测，不静默丢任务。

## 6. 阶段三：显式上下文替代 ThreadLocal 传递

### 问题

当前链路依赖：

- `UserContext.getCurrentUserId()`
- `KnowledgeSearchTool.currentKbId`
- `KnowledgeSearchTool.dynamicDescription`

这些 ThreadLocal 在 HTTP 请求线程内可用，但在 `CompletableFuture`、Reactor、手动线程或工具执行线程中可能丢失，导致：

- 用户 ID 为空。
- 知识库 ID 丢失。
- 跨请求上下文污染。
- 权限校验不稳定。

### 方案

新增显式上下文对象：

```java
public record AgentRunContext(
    Long userId,
    String sessionId,
    Long appId,
    List<Long> knowledgeBaseIds,
    Set<String> skillIds,
    boolean aioSandbox
) {}
```

后续工具执行使用显式上下文：

```java
public record ToolExecutionContext(
    Long userId,
    String sessionId,
    Long appId,
    Long defaultKnowledgeBaseId,
    List<Long> knowledgeBaseIds,
    boolean aioSandbox
) {}
```

本阶段不改工具协议，只改服务端执行上下文：

- HTTP 入口读取一次 `UserContext`。
- Agent 编排过程显式传递 `AgentRunContext`。
- `KnowledgeSearchTool` 不再依赖 `ThreadLocal` 保存 `currentKbId`。
- 异步任务参数中显式携带 `userId`、`sessionId`、`kbId`。

### 验收标准

- 工具执行线程切换后仍能拿到正确用户和知识库。
- 并发两个用户请求时，知识库检索不会互相串。
- `KnowledgeSearchTool` 不再需要调用 `clearCurrentKbId()` 做清理。

## 7. 阶段四：拆分 AgentServiceImpl

### 问题

`AgentServiceImpl` 同时负责：

- 会话校验。
- 沙箱创建。
- 历史消息。
- system prompt 构建。
- App 配置加载。
- Skill 过滤。
- 工具过滤。
- 知识库描述注入。
- PlanAgent 调用。
- KnowledgeEnhancer 调用。
- ReactAgent 调用。
- token 记录。
- 同步和流式两条链路。

职责过重，并且同步和 SSE 链路存在重复逻辑。

### 方案

拆出几个边界清晰的组件：

| 组件 | 职责 |
| --- | --- |
| `AgentRunContextBuilder` | 构建用户、会话、App、Skill、知识库上下文 |
| `AgentToolResolver` | 按沙箱类型、App Skill 配置过滤工具 |
| `AgentPromptBuilder` | 构建 system prompt、文件上下文、知识库描述 |
| `AgentPlanningService` | 调用 PlanAgent 并记录 planner token |
| `AgentKnowledgeContextService` | 调用 KnowledgeEnhancer 并拼接增强上下文 |
| `AgentExecutionPipeline` | 统一同步与流式执行前的准备流程 |

统一准备结果：

```java
public record PreparedAgentRun(
    AgentRunContext context,
    ConversationSession session,
    List<ChatMessage> history,
    String userMessage,
    String systemPrompt,
    String plan,
    List<Tool> tools,
    List<ToolDefinition> toolDefinitions
) {}
```

`AgentServiceImpl` 保留为应用服务入口，只协调调用，不再堆业务细节。

### 验收标准

- 同步聊天和流式聊天共用同一套准备逻辑。
- App 加载、Skill 过滤、工具过滤、知识增强不再复制两份。
- 修改工具过滤策略时，不需要同时改 `chat()` 和 `chatStream()`。

## 8. 阶段五：RAG 性能与可靠性

### 问题

RAG 当前方向正确，但存在几个工程问题：

- `KnowledgeServiceImpl.search()` 对每个结果查询文档所有 chunk，再内存过滤，存在 N+1。
- Milvus insert/search/delete 缺少统一状态检查。
- Rerank 使用 LLM 打分，输出格式不稳定。
- 多 query、多知识库检索没有明确并发上限。
- MySQL 与 Milvus 之间可能出现部分成功状态。

### 方案

1. 优化 chunk 查询：

```java
Optional<KnowledgeChunkEntity> findByDocumentIdAndChunkIndex(Long docId, int chunkIndex);
```

必要时增加批量查询，按 `(docId, chunkIndex)` 映射结果。

2. Milvus 状态检查：

- 对 `R<?>` 检查成功状态。
- 失败时抛出包含 collection、userId、kbId、docId 的异常。
- insert/search/delete 使用统一包装方法。

3. RAG 并发限制：

- Query Rewrite 结果数量设上限，例如 3。
- 检索并发使用 `ragExecutor`。
- 对每个用户或每次请求限制最大并发任务数。

4. Rerank 结构化：

- 要求 JSON 输出。
- 严格解析。
- 解析失败时按向量分数降级。

5. 状态一致性：

- 文档处理任一关键步骤失败时标记 `FAILED`。
- 不允许 Milvus 写入失败但文档显示 `READY`。

### 验收标准

- RAG 检索 SQL 次数可控。
- Milvus 失败时错误清晰。
- Rerank 失败不影响基础向量检索降级。
- 文档状态能真实反映向量是否可检索。

## 9. 阶段六：SSE 链路整改

### 问题

当前 SSE 能工作，但存在工程风险：

- 服务层内部手动 `subscribe()`。
- 流式工具执行手动创建心跳线程。
- 取消、错误、完成三种状态的资源释放逻辑分散。
- 部分消息保存逻辑可能在中断和完成分支重复。

### 方案

- `Controller -> AgentService -> ReactAgent` 全链路返回 `Flux<SseEvent>`。
- 不在 service 内部手动 `subscribe()`。
- 使用 `Flux.interval()` 或统一 `ScheduledExecutorService` 发送 heartbeat。
- 使用 `doFinally(signalType -> cleanup())` 统一清理。
- 明确处理：
  - `onComplete`
  - `onError`
  - `cancel`
- 中断时保存 partial assistant message，但保证只保存一次。

### 验收标准

- 客户端断开后不再继续推送事件。
- 无残留心跳线程。
- SSE 正常完成、异常、中断都能落库一致。
- 前端收到的事件顺序稳定。

## 10. 阶段七：测试与可观测性

### 测试范围

需要补充：

- `@Async` 真异步测试。
- 线程池切换后上下文不丢失测试。
- 同步和流式准备结果一致性测试。
- 文档状态流转测试。
- Milvus 失败分支测试。
- Rerank 降级测试。
- SSE 完成、错误、中断、heartbeat 测试。
- 多用户并发隔离测试。

### 日志与指标

统一记录：

- `requestId`
- `userId`
- `sessionId`
- `appId`
- planner/executor 耗时和 token
- RAG rewrite/search/rerank 耗时
- 文档处理阶段和失败原因
- 线程池活跃数、队列长度、拒绝次数

## 11. 风险与处理

| 风险 | 处理 |
| --- | --- |
| 拆分 AgentServiceImpl 影响聊天链路 | 先抽纯准备逻辑，保持外部接口不变 |
| 移除 ThreadLocal 改动范围较大 | 先从 KnowledgeSearchTool 和异步任务开始 |
| 线程池配置不合理 | 初期保守配置，增加日志和拒绝次数监控 |
| RAG 状态检查暴露旧数据问题 | 允许旧文档重新处理或删除重建 |
| SSE Reactor 化影响前端 | 保持现有事件类型不变，只改后端实现 |

## 12. 最终完成标准

本整改完成后，应满足：

- 文档上传异步处理真实生效。
- 主要异步任务都有专用线程池。
- Agent、RAG、工具执行不依赖 ThreadLocal 传递关键上下文。
- `AgentServiceImpl` 从大而全类变成薄编排入口。
- RAG 检索没有明显 N+1，Milvus 错误可诊断。
- SSE 取消和资源释放可控。
- 有基础测试覆盖关键失败路径。

