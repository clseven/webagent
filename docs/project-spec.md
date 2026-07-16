# WebAgent 项目规范

> 本文档是项目的唯一规范来源。新增代码、重构、接入新能力前先读一遍对应章节。
> 有设计决策时在 [架构决策记录](#八架构决策记录) 里补一条。

---

## 一、分层职责

项目分为五层，每层职责边界清晰，**不得跨层调用或把上层逻辑下沉**。

### 1.1 各层定位

| 层 | 代表类 | 职责 | 禁止做的事 |
|---|---|---|---|
| **Controller** | `AgentController` | 接收 HTTP 请求，返回 SSE/JSON | 包含业务逻辑 |
| **AgentService** | `AgentServiceImpl` | 编排规划与执行流程，注册 Hook | 自己实现工具逻辑；直接操作沙箱 |
| **Tool** | `*Tool` | 封装一个原子操作，返回字符串结果 | 直接调 LLM；操作消息列表；持久化 |
| **Hook** | `ReactAgent.*Hook` | 拦截/注入消息，处理副作用 | 包含复杂业务逻辑；直接调沙箱 |
| **LlmService** | `BaseLlmServiceImpl` | HTTP 调用 LLM API，协议转换 | 知道业务语义；操作对话历史 |
| **AIO 层** | `AioClient`, `AioFileApi` 等 | 封装沙箱 REST API | 理解 Agent 逻辑 |

### 1.2 消息流转规则

- `ChatMessage`：对话历史载体，可持久化，**不携带大体积二进制数据**（如图片 base64 应仅在内存流转，不写 DB）
- `LlmMessage`：LLM 协议级消息，仅在 `BaseLlmServiceImpl` 内部使用，不向上暴露
- Tool 的 `execute()` 返回值是**纯字符串**，由 ReactAgent 追加为 `role=tool` 消息，Tool 本身不操作消息列表

### 1.3 数据流向

```
用户请求
  → AgentService（编排）
    → PlanAgent（规划，当前沿用 executorLlm）
    → ReactAgent（执行，executorLlm）
      → Tool.execute()（原子操作）
        → AIO 层（沙箱 API）
      → PostToolUseHook（可注入额外消息，图片观察可调用 visionLlm）
      → LlmService.chatWithToolsStream()（下一轮 LLM 调用）
```

---

## 二、异常处理规范

三层有三种不同策略，**不得混用**。

### 2.1 Tool 层 — 吞掉，返回错误字符串

Tool 是 LLM 的"感知器"，异常要转成 LLM 能读懂的文字，不能让堆栈冒泡到 Agent 循环。

```java
@Override
public String execute(String sessionId, Map<String, Object> arguments) {
    try {
        // 正常逻辑
        return "操作成功：...";
    } catch (Exception e) {
        log.error("操作失败: path={}", path, e);          // 必须传 e，不能只传 e.getMessage()
        return "错误：操作失败 - " + e.getMessage();       // 统一格式见第三章
    }
}
```

**规则：**
- `execute()` 必须有顶层 try-catch，不允许异常透传
- catch 块必须打 `log.error`，且 `e` 作为最后参数（保留堆栈）
- 不允许 catch 后静默忽略（既不 log 也不返回错误）

### 2.2 Service 层 — 向上抛，交给框架

Service 层异常交给上层处理，不自己吞掉。已有 `LlmErrorPolicy` 处理 LLM 异常，`AioApiException` 处理沙箱异常，遵循现有机制。

```java
// ✅ 正确：明确抛出，携带上下文
throw new AioApiException("读取 Sandbox 文件失败: " + path);

// ❌ 错误：吞掉后返回降级值，调用方不知道出错了
try { ... } catch (Exception e) { return ""; }
```

### 2.3 AIO 层 — 抛 AioApiException

AIO 层统一抛 `AioApiException`，不使用 `RuntimeException` 或其他自定义异常。
返回体中 `success=false` 时等同于失败，应转换为异常向上抛出，不返回空对象。

### 2.4 异常日志格式

```java
// ✅ 正确：传 e 作为最后参数，框架自动打印堆栈
log.error("图片加载失败: path={}", path, e);
log.warn("LLM 重试: attempt={}", attempt, e);

// ❌ 错误：只打消息，丢失堆栈，线上排查困难
log.error("图片加载失败: {} - {}", path, e.getMessage());
```

---

## 三、Tool 返回值格式规范

Tool 的 `execute()` 返回字符串，LLM 直接读取，**格式要一致，LLM 才能正确理解**。

### 3.1 成功

简洁描述操作结果，不加前缀：

```
截图成功！文件路径: /home/gem/temp/xxx.png，大小: 12345 bytes
已读取文件内容（1024 字节）
命令执行完成，退出码: 0
```

### 3.2 参数校验失败

```
错误：path 不能为空
错误：action_type 必须是 CLICK、SCROLL、TYPING 之一
```

格式：`错误：{字段名} {校验原因}`

### 3.3 执行失败（catch 块）

```
错误：截图失败 - connection refused
错误：文件读取失败 - /home/gem/xxx.png 不存在
```

格式：`错误：{操作描述}失败 - {e.getMessage()}`

### 3.4 禁止的格式

```java
// ❌ 不统一的动词前缀
return "截图失败：" + e.getMessage();
return "读取失败：" + e.getMessage();
return "解析失败：" + e.getMessage();

// ❌ 英文（LLM 会用中文回复用户，夹杂英文错误信息不协调）
return "Error: file not found";
```

---

## 四、日志规范

### 4.1 级别使用

| 级别 | 使用场景 | 示例 |
|---|---|---|
| `ERROR` | 需要关注的错误，影响功能 | LLM 调用失败、沙箱连接断开 |
| `WARN` | 可恢复的异常、非预期但不影响主流程 | 工具执行被 Hook 阻止、重试触发 |
| `INFO` | 正常流程中的关键节点 | 工具执行开始/完成、LLM 请求发出 |
| `DEBUG` | 调试细节，生产环境不输出 | 请求体内容、中间状态 |

### 4.2 语言

**统一使用中文**，包括日志消息、注释。AIO 层现有的英文日志在后续修改时顺手改为中文。

### 4.3 结构化日志

使用占位符，不用字符串拼接：

```java
// ✅ 正确
log.info("截图成功: size={} bytes, path={}", screenshot.length, filePath);
log.error("工具执行失败: tool={} sessionId={}", toolName, sessionId, e);

// ❌ 错误
log.info("截图成功，大小：" + screenshot.length + "，路径：" + filePath);
```

### 4.4 敏感信息

日志中**不打印** API Key、用户上传的文件内容（路径可以，内容不行）、base64 图片数据。

---

## 五、参数校验规范

### 5.1 Tool 入参校验

Tool 的 `execute()` 入参来自 LLM，不可信，必须做防御性校验。

```java
// ✅ 统一用 == null || isBlank()，字符串类型都要两步
String path = (String) arguments.get("path");
if (path == null || path.isBlank()) {
    return "错误：path 不能为空";
}

// ❌ 只判 null，空字符串会透传到沙箱报奇怪错误
if (path == null) { ... }

// ❌ 依赖 catch 兜底，错误信息不友好
String path = (String) arguments.get("path");
// 直接用，等 NPE
```

### 5.2 类型转换

LLM 传来的参数类型不保证，`Integer`/`Boolean` 做强转前先判断：

```java
// ✅ 用 instanceof 模式匹配
Object recursive = arguments.get("recursive");
boolean isRecursive = recursive instanceof Boolean b && b;

// ❌ 直接强转，LLM 传 "true" 字符串时会 ClassCastException
boolean isRecursive = (Boolean) arguments.get("recursive");
```

### 5.3 默认值策略

参数缺失时：
- **路径类参数**：报错，不给默认值（路径错了比没操作更危险）
- **可选配置类参数**（如 `recursive`、`max_depth`）：给合理默认值并注明

---

## 六、注释规范

### 6.1 类注释（必须）

所有 `public class` 必须有 Javadoc，结构：

```java
/**
 * 一句话说明这个类是什么。
 *
 * <h3>用途</h3>
 * <p>详细说明使用场景。</p>
 *
 * <h3>注意</h3>
 * <p>使用限制或特殊行为。</p>
 */
```

### 6.2 方法注释（public 必须，private 按需）

- `public` 方法必须有 Javadoc，说明参数和返回值含义
- `private` 方法逻辑复杂（超过 15 行）或名字不够自解释时加注释
- 简单的 getter/setter、工厂方法可以只写一行说明

### 6.3 字段注释

- 类中的常量和重要字段必须有 `/** */` 注释
- Lombok 自动生成的字段描述清楚含义即可

### 6.4 禁止的注释

```java
// ❌ 废话注释
// 获取路径
String path = arguments.get("path");

// ❌ 注释掉的代码（直接删，有 git 历史）
// String old = doOldThing();

// ❌ TODO 超过两周没处理的（要么做，要么删）
// TODO: 以后再优化
```

---

## 七、扩展点 Checklist

### 7.1 新增 Tool

1. 在 `service/tool/` 下新建 `XxxTool.java`，实现 `Tool` 接口
2. 加 `@Component` 注解（Spring 自动注册到工具列表）
3. `getDefinition()` 中填写：工具名、描述（给 LLM 看的）、参数 JSON Schema、适用沙箱类型（`"AIO"` 或 `"ALL"`）
4. `execute()` 遵循本文第二、三章规范
5. 如果工具需要持久化数据或注入额外消息，通过 Hook 机制处理，不在 `execute()` 里直接操作

### 7.2 新增 LLM 提供方

1. 在 `service/impl/` 下新建 `XxxLlmServiceImpl.java`，继承 `BaseLlmServiceImpl`
2. 构造函数从 `AgentConfigProperties` 读取对应节点的配置（apiUrl / apiKey / model）
3. 如有厂商专有参数（如 DeepSeek 的 `thinking`），重写 `customizeRequestBody()`，**只在这里加专有参数**
4. 在 `AgentConfigProperties.Llm` 下新增对应配置节点
5. 在 `application.yml` 填写真实配置值（api-key 不提交到 git）
6. 用 `@Qualifier` 注入并决定用于 `executorLlm`、`plannerLlm` 或 `visionLlm`

### 7.3 新增 Hook

1. 确认用哪种 Hook 类型：
   - `PreToolUseHook`：拦截工具调用（返回非 null = 阻止执行）
   - `PostToolUseHook`：工具执行后注入消息（返回非 null = 追加消息到对话）
   - `StopHook`：Agent 准备结束时强制继续（返回非 null = 注入消息继续循环）
2. 在 `AgentServiceImpl` 中编写私有方法返回 Hook 实例
3. 在两处 `new ReactAgent(...)` 后都注册该 Hook（同步路径 + 流式路径）
4. 如果 Hook 需要跨工具和 Hook 传递数据，用 `@Component` 的共享 buffer（参考 `ImageBuffer`），不用静态变量

---

## 八、架构决策记录

记录"为什么这么设计"，防止三个月后推翻自己的决定。

---

### ADR-001 图片注入通过 PostToolUseHook 而非 PreLlmCallHook

**时间**：2026-06

**决策**：图片数据通过 `PostToolUseHook` 在工具执行后注入消息，而非在每次 LLM 调用前扫描全量消息。

**排除方案**：
- PreLlmCallHook 全量扫描：每次 LLM 调用都扫所有消息找图片路径，触发频率高且大多数扫描无意义
- 自动注入（路径出现即注入）：LLM 不一定需要看到每张图片，应由 LLM 主动决定

**理由**：事件驱动，只在 LLM 主动调用 `view_image` 工具时才触发，精确且无冗余操作。

---

### ADR-002 通用 LLM 用 GenericLlmServiceImpl，专有参数才建子类

**时间**：2026-06

**决策**：OpenAI 兼容协议的模型统一用 `GenericLlmServiceImpl`，只有需要发送厂商专有请求字段时才新建子类。

**排除方案**：每个模型都建一个子类（如 AgnesLlmServiceImpl）。

**理由**：子类过多时扩展负担大，配置驱动更灵活；`customizeRequestBody()` 钩子已提供差异化扩展点。

---

### ADR-003 ChatMessage 与 LlmMessage 分为两层

**时间**：2026-05

**决策**：对话历史用 `ChatMessage`（可持久化），LLM 协议层用 `LlmMessage`（仅内存），不合并为一个类。

**理由**：
- `ChatMessage` 需要持久化到 DB，不能携带大体积字段（如 base64 图片）
- `LlmMessage` 的 `contentParts` 等字段只在 LLM 调用时需要，持久化无意义
- 分层后各自演进互不影响

`ChatMessage.contentParts` 是唯一例外：仅内存使用，不持久化，用于运行时图片注入。

---

### ADR-004 DeepSeek 负责规划执行，Agnes 负责视觉观察

**时间**：2026-07

**决策**：`executorLlm` 使用 DeepSeek，负责 ReAct 执行、工具选择和最终回答；当前 `AgentPlannerService` 的 PlanAgent 也沿用 `executorLlm`，因此规划阶段同样由 DeepSeek 驱动。新增 `visionLlm` 使用 Agnes（`agnes-2.0-flash`），专门处理 `view_image` 后的图片观察。

**理由**：DeepSeek 更适合作为主 Agent 的规划和工具执行模型；Agnes 具备多模态视觉能力，适合作为图片观察模型。`view_image` 仍由工具加载图片，`PostToolUseHook` 调用 `visionLlm` 得到文本观察结果，再注入给 DeepSeek 主 Agent 继续推理，避免要求主执行器直接处理图片字节。

**约束**：`visionLlm` 不添加 DeepSeek 的 `thinking` 等专有参数；Agnes 只输出客观观察结果，不接管最终回复。

---

### ADR-005 轻量输入可跳过 PlanAgent

**时间**：2026-06

**决策**：对“你好”“谢谢”“再见”等确定无需规划模型的纯社交输入，在 `AgentServiceImpl` 前置调用 `LightweightChatRouter`。命中后跳过 `PlanAgent`，但仍进入 `ReactAgent` 生成最终回复。前端提供“规划模式”开关，非轻量请求按该开关决定是否调用 `PlanAgent`。

**排除方案**：
- 命中后本地固定回复：成本最低，但会绕过执行器模型，回复风格和上下文处理不一致。
- 在前端短路：会绕过后端历史保存、权限校验和多端一致性。

**理由**：`PlanAgent` 与 `ReactAgent` 已通过 `plan` 参数解耦，`plan=null` 时 `ReactAgent` 可以独立执行。轻量路由只做保守的确定性匹配，可以减少不必要的规划模型调用，同时不影响“继续”“确认”“安装吧”等依赖上下文或需要工具观察的请求继续走完整 Agent 链路。

---

### ADR-006 Milvus 可通过配置关闭

**时间**：2026-06

**决策**：`rag.milvus.enabled=false` 时不创建 `MilvusClient`，也不执行 Milvus 集合初始化；`VectorStoreService` 改由空实现承接，写入和删除向量时只记录跳过日志，检索返回空结果。

**排除方案**：
- 启动后懒连接 Milvus：能减少启动依赖，但第一次 RAG 操作仍会因本地未启动 Milvus 失败。
- 关闭整个知识库模块：影响文档上传、正文切片和文件预览，范围超过本地轻量启动需求。

**理由**：Milvus 是 RAG 向量检索的外部依赖，但本地开发并不总是需要检索能力。用配置开关保留默认生产行为，同时允许轻量启动；空实现保证上层 Bean 依赖稳定，避免调用方到处判断向量库是否存在。

---

### ADR-007 工作区目录记忆只记录 `/home/gem` 可见目录树元数据

**时间**：2026-06

**决策**：大模型工作区记忆以 `WorkspaceDirectoryMemoryService` 维护前端工作区面板可见的 `/home/gem` 非隐藏目录树，只保存路径、父路径、目录标记、深度、文件大小、沙箱 ID 和可见状态等元数据。

**排除方案**：
- 读取文件正文并做摘要：会引入隐私和成本风险，也会在用户未显式要求时扩大文件读取范围。
- 复用 RAG 文档切片：RAG 面向用户上传/知识库正文检索，目录记忆只需要告诉模型有哪些可见路径，语义目标不同。
- 扫描整个系统根目录：会把运行时、系统文件和隐藏配置暴露给模型，超出前端展示范围。

**理由**：前端工作区面板已经把用户可见边界定义为 `/home/gem` 下的非隐藏路径。目录记忆与该边界一致，可以帮助规划器和执行器理解工作区结构，同时避免后台读取文件内容。需要正文时仍由模型显式调用文件读取工具。

---

### ADR-008 执行器提示词改为按工具能力分段组装

**时间**：2026-06

**决策**：`ReactAgent` 不再维护一整块固定 system prompt，而是通过 `ReactPromptAssembler` 按当前真实工具定义加载 identity、workspace、browser、skill、subagent、MCP 和 tools 等 section；动态上下文和任务策略统一放在动态边界之后。`PlanAgent` 通过 `PlannerPromptAssembler` 生成策略层提示词，MCP 约束只在规划资料涉及 MCP 时注入。

**排除方案**：
- 继续在工具 description 里堆工作流补丁：会让工具定义承担 system prompt 的职责，且不同工具之间规则重复。
- 直接允许普通工具并行调用：当前执行循环仍按单个 tool call 处理，一次响应多个普通工具调用会有丢失风险。

**理由**：分段组装让稳定 section 更容易复用和缓存，也减少普通对话的无关指令噪声。工具 description 回归“工具是什么、参数怎么填”，跨工具选择、MCP 安装流程和浏览器操作策略放回 system prompt。并行能力先保守地交给 `run_subagent`，等执行循环完整支持多 tool call 后再放开普通工具并行提示。

---

### ADR-009 Agent 编排层不直接实现技能、工具和 Hook 运行时

**时间**：2026-06

**决策**：`AgentServiceImpl` 只保留会话校验、规划/执行串联、消息保存和 token 记录等编排逻辑；技能发现与读取、工具上下文构建、知识库上下文、规划器调用、`ReactAgent` 创建和 Hook 注册分别下沉到独立 service。

**排除方案**：
- 在同步对话和流式对话里继续各自维护一份前置处理：短期改动少，但新增技能、知识库或工具过滤规则时容易两边漏改。
- 让 `ConversationServiceImpl` 继续直接处理技能运行时：会把会话持久化服务和沙箱技能读取耦合在一起，边界不清晰。

**理由**：同步和流式对话共享同一套 `AgentTurnContext`、工具上下文和规划服务后，新增技能发现规则、Hook 或工具过滤只需要改对应 service。Agent 编排层不再直接读沙箱技能或维护工具状态，职责边界更接近“提示词与流程编排”。

---

### ADR-010 运行时 TodoState 作为 Agent 计划执行与反思的硬状态

**时间**：2026-07

**决策**：保留 `PlanAgent` 的任务前建模职责，新增会话内内存态 `AgentTodoService` 保存运行时 `TodoState`。执行器通过 `todo_write` 工具显式更新 todo、成功信号、证据和阻塞原因；`FinalTodoGuardHook` 在最终回答前检查关键 todo 是否已 `completed` 或 `blocked`。

**排除方案**：
- 用 `TodoState` 取代 `PlanAgent`：会把任务前建模和执行中状态混在一起，破坏已有规划/执行分层。
- 让 `TodoState` 调度工具或并行批次：会把看板职责扩大成执行器职责，和 `ReactAgent`、后续 `ToolExecutionPolicy` 边界冲突。
- 第一版跨会话持久化 todo：会引入清理、迁移和历史兼容问题，超过当前“单轮运行时计划”目标。

**理由**：`PlanAgent` 继续提供目标状态、成功信号和初始策略；`TodoState` 只记录执行中的可检查清单和证据；`FinalTodoGuardHook` 防止仍有未完成 todo 时直接最终回答。这样能增强多步任务闭环，又不改变工具执行、并行策略和视觉模型拆分成果。

**约束**：第一版 `TodoState` 仅保存在内存中，按 `sessionId` 管理；`completed` 缺少 `evidence` 时先返回提醒并由最终门禁拦截补证据；`blocked` 必须包含 `blocker`；未完成 todo 不允许被静默删除，只能继续推进或显式取消/阻塞。
 
### ADR-011 沙箱视图通过用户级 token 和同源 URI 代理暴露

**时间**：2026-07

**决策**：沙箱视图入口使用 `/sandbox-view/{token}/...` 同源 URI 代理。`SandboxViewTokenService`
只在 token 中保存 `userId`，不保存 AIO endpoint；每次 HTTP 或 WebSocket 请求都按用户查询当前最新
endpoint。代理层支持常见 HTTP 方法、Location 头改写和 WebSocket Upgrade 转发，前端默认打开
`/vnc/index.html?autoconnect=true` 浏览器视图，并通过本地视图菜单切换终端、VSCode 和文件。

**排除方案**：
- 直接把 AIO endpoint 返回给浏览器：云端部署会暴露 `127.0.0.1` 或随机端口，浏览器无法访问且有安全边界问题。
- token 固定 endpoint：沙箱重建或端口变化后旧 token 会继续代理到旧端口，容易产生 502。
- 只代理 `/code-server/`：无法覆盖 AIO 自带的浏览器、终端、文件和 WebSocket 通道。

**理由**：该方案更接近 OpenSandbox Ingress 的 URI mode，既保持公网同源访问，也能复用 AIO 内部
VNC、code-server 和 terminal 路由。endpoint 动态解析可以适配用户级沙箱绑定和沙箱重建；Location
改写和 WebSocket 代理则保证 code-server/noVNC 这类视图不会跳出同源代理或在 Upgrade 阶段失败。

### ADR-012 收尾治理改为 Stop Hook 证据自检，删除 `AgentSearchPolicyHook`

**时间**：2026-07

**决策**：删除基于硬规则的 `AgentSearchPolicyHook`（Pre Hook），把“该不该继续/该不该收尾”的判断挪到 Stop Hook 让模型基于证据自判。`FinalTodoGuardHook` 分两层：先看 TodoState 前置硬信号（未闭环/缺证据直接拦），干净后返回 `VERIFY_CANDIDATE` 决策。`ReactAgent` 保存自检前的首版候选答案并注入一次证据自检提示；自检轮继续调工具表示候选失效，工具结果消化后重新生成并验证新候选；自检轮不调工具而再次收尾表示通过，执行器原样放行首版候选，不采用自检轮重新生成的正文。harness 仍不解析模型自检文本，只根据“工具调用 / 再次收尾”两类协议行为判定。

**排除方案**：
- 保留 `AgentSearchPolicyHook` 的方向闸/预算闸：不看证据，只按搜索词正则和写死次数上限判断，简单任务被多搜、复杂任务被掐断；方向判断靠英文标识符正则，对纯中文请求失效；且只约束搜索，读文件/跑命令等不受治理，不一致。
- 为搜索单造一套编排：搜索只是工具之一，不该有特殊待遇，靠通用 `ReactAgent` 循环消化发散即可。
- harness 解析 `PASS/REVISE` 文本再判定：增加脆弱的文本解析耦合；当前方案继续靠模型二选一行动，harness 不解析标记。
- 连续注入两次自检、第三次强制放行：没有根据“自检已通过”提前结束，而且会让无问题答案被重复生成和改写；改为每个候选只验证一次。

**理由**：“该不该收尾”本质是需要看证据才能判断的决策，硬塞进 Pre Hook 用正则常量干只是创可贴。移到 Stop Hook 后，方向判断由自检第 1 条（模型拆解子问题自然发现跑题）接管，搜索不再有专属逻辑——读文件、跑命令、搜索一视同仁接受证据验收。候选快照把“答案生成”和“答案验收”分开：验收通过不改原答案，发现问题才通过工具补查触发新候选；`MAX_ITERATIONS` 继续防止反复补查不收敛。

**约束**：`ReactAgent` 由工厂按会话新建、一个实例只跑一条路径（`run` 或 `runStream`），候选快照使用实例字段，天然按会话隔离；`StopHook` 返回结构化 `StopDecision`，只有 `VERIFY_CANDIDATE` 会保存当前答案；自检期间出现任何工具调用都必须先使旧候选失效，即使该工具随后被 Pre Hook 拦截；自检提示要求检查过程只进入 reasoning，最终 `answer` 事件和持久化正文都使用通过验证的候选。

### ADR-013 文件状态检查用写前现场 re-read hash，不追踪中间事件

**时间**：2026-07

**决策**：新增 per-session `FileCognitionState`（`{路径→内容 hash}`，LRU 上限 256）与 `FileStateCheckHook`（同时为 Pre 与 Post Hook）。read 后由 Post Hook 存内容 hash；write_file/file_replace 前由 Pre Hook 现场验证：先判新建 vs 覆盖（`SandboxClient.fileExists` 经 `/v1/file/list` 列父目录间接判断，新建放行），覆盖已有文件时没读过则拦、re-read 算当前 hash 与记录不符则拦、一致则放行并刷新记录；写后由 Post Hook 清除该路径记录。大文件（>1MB）降级为只查"读过没"，跳过 hash。

**排除方案**：
- 追踪 read 和 write 之间到底谁改了文件：shell 是黑盒、模型自述不可全信，且要解析 shell 命令，复杂且不可靠。
- 用 mtime 判过时：`/v1/file/read` 不返回 mtime，read 时需额外调 list 取 mtime（0 额外 API 优势消失）；且 mtime 可被 `touch`/`cp -p` 重置，不如内容 hash 可靠。
- 在工具内部做匹配失败兜底（如 file_replace 的 old_str）：那是改一半才发现，state checks 执行前拦更早，且防"恰好匹配但内容已变"的误替换。

**理由**：走法 C（写前现场 re-read hash）让 shell 难题消失——不管中间是 shell、并发工具还是外部改的，写前 hash 一比就知道；不波及其他文件，只验证要写的那个；不依赖模型自述，harness 自己算。read-before-edit 与 staleness 合并：写前那次 re-read 同时确认"读过"和"没过时"。主要价值在并发落地后防 TOCTOU（见 ADR-014），单线程下基本不触发、低开销待命。

**约束**：`SandboxClient` 新增 `fileExists` 默认返回 false（仅 AIO 客户端提供真实判断），无法判断存在性的沙箱不会误拦新建；State Checks 受 `agent.hook.state-check-enabled` 配置开关控制，出问题可立即关闭恢复无校验；并发落地后，本 hook 的 re-read+verify+write 必须整体在 per-session 写锁临界区内（当前单线程不涉及，待并发扩展时落实）。

### ADR-014 工具并发执行：READ 并发、WRITE/EXCLUSIVE 串行，按原序对齐

**时间**：2026-07

**决策**：`Tool` 接口新增 `getSideEffect()` 返回 `ToolSideEffect`（READ/WRITE/EXCLUSIVE，默认 EXCLUSIVE 最保守）；仅 `web_search` 显式标 READ，其余默认 EXCLUSIVE（最小可行起点，方案 §9.5）。`LlmResponse` 新增 `getToolCalls()` 列表（保留 `getToolCall()` 兼容），`BaseLlmServiceImpl` 同步取全部 tool_calls、流式按 OpenAI `index` 分开累积并按序发出。新增 `ToolScheduler`：READ 批次并发（限流 3）、遇 WRITE/EXCLUSIVE 先 flush 当前 READ 批次再串行执行、结果按模型原序对齐。`ReactAgent` 同步与流式两条主循环改为遍历 `getToolCalls()` 交调度器，多 tool_call 用一条带 `tool_calls` 数组的 assistant 消息 + 各跟一条 tool 结果（OpenAI 并发协议）。

**排除方案**：
- 全量并发（所有 READ 类一起跑，含 read_file/grep）：沙箱并发访问与 ImageBuffer 等隐式共享状态会撞，需先解决 per-session 锁和 buffer 关联键，风险高。最小可行起点只对纯网络读（web_search）开并发，规避此风险。
- 解析模型自检输出再决定并发度：增加脆弱的文本解析耦合；调度器只按副作用类型静态分组即可。
- 流式仍用单 builder 合并所有 tool_calls：会把并发的多个工具调用揉成一个损坏的调用，必须按 index 分开累积。

**理由**：联网调研场景模型一轮抛 3-5 个搜索时，串行 = 3-5 倍延迟，并发 ≈ 1 倍，收益确定。真正的地基是先让 LLM 接入层能拿到并正确解析"一轮多个 tool_call"（流式按 index 累积），再加调度器。默认 EXCLUSIVE 保证漏标工具不会因误并发导致数据竞争——失去并发收益是可接受的，标错才危险。

**约束**：`ToolScheduler` 受 `agent.hook.concurrent-tool-execution-enabled` 开关控制，置 false 退化为串行遍历（仍遍历列表，回滚不影响多 tool_call 协议）；SSE 事件按模型原序回灌，前端暂不改（并发折叠 UI 见 `docs/superpowers/specs/2026-07-07-frontend-concurrent-tools-ui-todo.md`，待后端稳定后再做）；并发下 ImageBuffer 按 sessionId 关联的隐患在最小可行起点（只 web_search 并发）下不触发，扩大并发前需改为按 tool_call_id 关联或对 view_image 标 EXCLUSIVE；流式并发工具暂用 `executeTool`（非心跳版），单工具仍走 `executeToolWithHeartbeat`。

---

### ADR-015 文档异步处理拆到独立 Bean，避免 @Async 自调用失效

**时间**：2026-07

**决策**：知识库文档异步处理（解析→切片→向量化→沙箱同步）从 `KnowledgeServiceImpl` 拆到独立 Bean `KnowledgeDocumentProcessor`，用 `@Async("knowledgeTaskExecutor")` 指向 `AsyncConfig` 定义的有界线程池（core=2/max=8/queue=100/`CallerRunsPolicy`）；`KnowledgeServiceImpl.upload`/`replaceDocument` 改为跨 Bean 调用 `documentProcessor.processDocumentAsync(...)`。前端 `Knowledge.js` 在上传/替换完成后启动轮询（每 3s `loadDocuments`），直到所有文档稳定为 `READY`/`FAILED` 停止，组件卸载时清理定时器。

**排除方案**：
- 同类自调用 + `@Lazy` 注入自身代理：能生效但保留自调用歧义，且 `processDocumentAsync` 不在 `KnowledgeService` 接口里，需注入 impl 类型，不优雅。
- `AopContext.currentProxy()`（开 `exposeProxy=true`）：侵入业务代码、耦合 Spring AOP API。
- 维持同步、删掉误导性的 `@Async`：不动前端最省事，但大 PDF 会阻塞上传请求线程、超时风险高，且与"异步处理"的产品语义矛盾。

**理由**：Spring `@Async` 靠代理实现，同类内部方法直接调用（`this.processDocumentAsync`）不走代理，注解完全失效——原实现实际是同步执行，上传接口要等解析+切片+向量化全部完成才返回，所谓"立即返回"并不成立。拆到独立 Bean 是消除自调用的根本解，顺带用有界线程池替代默认无界 `SimpleAsyncTaskExecutor`，一并解决并发上传线程失控风险。真异步后上传接口立即返回 `PENDING`，故前端必须配套轮询，否则界面会停在"等待处理"。

**约束**：`knowledgeTaskExecutor` 队列满时 `CallerRunsPolicy` 回退为调用线程执行，天然限流但会让该次上传同步阻塞，属可接受的背压；异步方法无事务包裹，中途失败时已写入的切片/向量不会回滚，`FAILED` 文档可能残留脏数据，需靠后续 `replaceDocument`/`deleteDocument` 清理；前端轮询固定 3s 间隔。

---

### ADR-016 智能体时间感知采用单轮快照与按需实时时间工具

**时间**：2026-07

**决策**：`AgentTurnContextService` 在每轮准备阶段通过可注入 `Clock` 的 `AgentTimeContextService` 只创建一次时间快照，默认时区由 `agent.time-zone` 配置（缺省 `Asia/Shanghai`）。该快照同时进入规划器上下文、普通执行器、社交轮和同轮派生的子智能体，并放在 `ReactPromptAssembler` 稳定提示词边界之后。另注册只读工具 `current_time`，仅在任务需要调用时刻的新时间或指定 IANA 时区时读取新快照。

**排除方案**：
- 只在应用启动时把日期写入固定 system prompt：长时间运行后日期会过期，并且每次变化都会污染稳定提示词前缀和上游缓存。
- 每个规划器、执行器和子智能体各自读取系统时钟：同一任务跨过秒、分钟或午夜时会得到不同上下文，测试也无法稳定复现。
- 把“当前日期”当成“最新事实”：知道时间不能证明政策、版本、价格或事件状态，仍需搜索或相应工具提供证据。
- 所有问题都强制调用时间工具：增加不必要的工具轮次和延迟；大多数相对日期问题用单轮快照即可回答。

**理由**：单轮快照让“今天、明天、本周”等表达在一条任务链中保持一致；动态边界后的注入保留稳定提示词缓存；可注入 `Clock` 使日期、星期、时区与 UTC 转换能确定性测试；`current_time` 则补足长任务中的新鲜时刻和跨时区查询，而不让普通对话承担额外工具开销。

**约束**：时间快照只表达时钟事实，不替代网络搜索或业务数据验证；`current_time.time_zone` 必须是合法 IANA 时区，非法值以工具层中文错误返回；子智能体必须继承父智能体快照，不在派生时重新取时；修改默认时区使用环境变量 `AGENT_TIME_ZONE` 或对应配置项。

---

### ADR-017 达到执行上限时保存协议检查点并在下一轮继续

**时间**：2026-07

**决策**：复用 `chat_message` 表保存 Agent 运行状态与恢复数据：`events_json` 继续承担前端展示和审计，新增 `run_status` 区分正常完成与 `PAUSED_MAX_ITERATIONS`，新增 `checkpoint_json` 保存模型协议级消息，包括角色、正文、reasoning、tool_call ID、并发调用分组、tool 结果及其顺序。单次 ReAct 运行上限调整为 200 轮；达到上限时展示提示但不把提示当作模型可见的任务结论，下一条用户消息优先恢复完整检查点。旧数据没有检查点时，从 `events_json` 生成明确标注的文本续作上下文，并移除历史末尾的通用超限提示。普通数据库历史仍加载最近 20 条，精确检查点不受 20 条限制；工作上下文估算超过 200K token 后才触发摘要压缩。

**排除方案**：
- 只把“超过上限”提示作为 assistant 历史传回模型：模型看不到已经执行的工具与结果，容易重复劳动或误判任务已经结束。
- 直接把 `events_json` 重新拼成 tool 消息：旧事件缺少可靠的 tool_call ID 和并发分组，无法保证符合模型工具调用协议。
- 仅依赖内存中的 Agent 状态：进程重启、负载均衡切换或用户稍后继续时会丢失执行现场。
- 新建独立运行表：长期可扩展性更强，但当前恢复数据与 assistant 暂停消息是一一对应关系，复用现有消息表能以更小改动完成可靠恢复。

**理由**：展示事件和模型协议状态面向不同消费者，不能互相替代。双层持久化让前端继续恢复完整过程，同时让下一轮 LLM 获得可验证的工具调用链；运行状态把“运行暂停”从“聊天答案”中分离，避免通用限制文案抹掉前面已经完成的工作。200K 压缩阈值保留长上下文模型的有效窗口，200 轮上限则只作为单次运行的安全边界，不再成为任务上下文丢失点。

**约束**：检查点 JSON 使用显式版本号，解析失败或版本不兼容时不得伪造协议消息，只能降级到事件文本；事件文本仅作为上下文说明，不声称恢复精确 tool_call 协议；数据库由 JPA 自动更新字段的部署方式必须先确认目标环境允许 schema update，生产环境若关闭自动更新需先执行等价迁移；压缩必须保持 assistant tool_calls 与后续 tool 结果的完整边界。

---

### ADR-018 RAG 重排使用独立模型配置与可切换提供方

**时间**：2026-07

**决策**：RAG 上层继续只依赖 `RerankService`；生产默认由 `DeepSeekRerankServiceImpl` 使用独立的 `RAG_RERANK_MODEL=deepseek-v4-flash` 批量重排，主流程模型继续由 `DEEPSEEK_LLM_MODEL` 独立控制。`rag.enhancement.rerank.provider` 在 DeepSeek 与现有 BGE 实现之间选择唯一 Bean。外部重排超时、HTTP 失败或响应协议非法时不重试，立即按 Milvus 向量分数降级。

**排除方案**：
- 让重排模型继承 `DEEPSEEK_LLM_MODEL`：主流程从 Flash 切到 Pro 会意外改变重排成本和延迟，模型角色发生耦合。
- 引入通用模型注册中心或动态路由器：当前只有两个固定重排协议，新增抽象层会扩大改动面。
- 继续让 2 核云服务器运行本地 BGE：真实检索已观察到一次 20 候选重排约耗时 38.84 秒，无法满足交互请求延迟。
- 完全关闭重排：延迟最低，但会丢失复杂语义排序能力。

**理由**：`RerankService` 已经提供足够的领域边界；新增一个提供方实现和独立配置即可让上层、主流程模型与供应商协议相互隔离。同一 DeepSeek 兼容协议内更换重排模型只需修改环境变量。有限候选、固定超时和向量降级让外部模型故障不会阻断知识库检索。

**约束**：重排日志不得记录查询全文、候选正文、API Key 或原始响应；第一版不自动重试；Knowledge 页面在出口按请求 `topK` 截断，Agent 路径继续使用 RAG 默认 `top-k`；本地 BGE 服务只有在 `provider=bge` 时参与运行。

---

### ADR-019 RAG 最终结果统一先按相关度过滤再截取 topK

**时间**：2026-07

**决策**：新增无状态 `RerankResultFilter`，统一用于 Agent 自动知识注入、`knowledge_search` 工具和 Knowledge 页面检索。默认最低相关度由独立配置 `RAG_RERANK_MIN_SCORE=0.8` 控制；Knowledge 页面可为单次测试请求调整阈值。所有入口必须先丢弃低于阈值的重排结果，再截取 topK；重排服务降级为向量排序时仍执行同一过滤规则。

**排除方案**：
- 只在前端隐藏低分结果：Agent 上下文和接口响应仍会包含低质量正文，不能真正降低幻觉风险。
- 把阈值写入知识库数据库：当前需求只要求默认门槛和页面单次调整，引入持久化字段、迁移和多知识库阈值合并会扩大耦合。
- 在 DeepSeek 重排实现内部直接过滤：调用方降低页面阈值时无法恢复已被模型适配层丢弃的候选，也会把业务质量策略耦合到单一提供方。

**理由**：最终分数由重排阶段产生，阈值应在重排后生效；共享无状态过滤器让不同入口保持相同顺序语义，同时不让 DeepSeek、BGE 或向量降级实现感知页面参数。默认 0.8 可过滤明显低相关结果，页面滑块允许用户针对当前查询调整召回与精度的取舍。

**约束**：相关度是 0 到 1 的排序分数，不表述为经过校准的概率；请求阈值越界时收敛到 0 到 1；没有结果达到阈值时返回空列表且 Agent 不注入知识上下文；`topK` 永远在阈值过滤之后应用。

---

### ADR-020 知识库检索使用独立请求开关并脱离轮次轻量策略

**时间**：2026-07

**决策**：聊天请求新增默认开启的 `knowledgeEnabled`，同步与流式入口都写入请求级 `UserContext`。当前 Agent 关联至少一个知识库且开关开启时，每条用户消息都先对全部关联知识库执行自动检索，不再受 `SOCIAL/TASK/AMBIGUOUS` 轮次策略控制；开关关闭时既不自动注入知识上下文，也不向模型暴露 `knowledge_search` 工具。聊天输入区提供独立知识库按钮，没有关联知识库时按钮置灰。

**排除方案**：
- 继续由 `TurnPolicy.LITE` 关闭知识库：问候式、短句式问题可能仍需要业务知识，分类误判也会导致漏检。
- 只在前端隐藏开关状态：旧客户端或模型工具仍可能绕过界面继续检索，无法形成后端能力边界。
- 把开关持久化到 Agent 应用：当前需求是用户对当前聊天请求即时控制，持久化会让不同会话共享状态并增加数据迁移。

**理由**：知识库是否使用是用户明确选择，不应由消息类型分类器替用户决定。请求级开关与联网搜索、规划开关保持同一传递方式；默认开启兼容既有自动增强行为，而后端同时控制上下文和工具可见性可保证关闭语义完整。

**约束**：没有 Agent 应用或应用未关联知识库时，开启开关也不得检索用户其他未关联知识库；自动检索必须使用应用关联的完整知识库 ID 集合；旧客户端未传参数时按开启处理；请求结束必须清理 ThreadLocal，流式异步线程必须显式复制开关状态。

---

### ADR-021 统一知识库检索流水线并区分重排与向量分数

**时间**：2026-07

**决策**：REST 搜索、`knowledge_search` 工具和 Agent 自动预检索统一复用 `KnowledgeService` 的多知识库检索入口。单次检索只执行一次 Query Rewrite、一次批量 Embedding、限定知识库集合内的向量召回、跨查询去重、一次全局 Rerank 和一次结果过滤；`KnowledgeEnhancerImpl` 只负责把结构化结果格式化为提示词，Tool 只负责参数校验和字符串返回。`RerankService` 返回带 `reranked` 来源标记的 `RerankResult`：最低相关度只过滤成功重排产生的分数，向量降级只按原始分数排序和截取 topK，不再使用旧的 `retrieve.min-score=0.5` 预过滤。本决策取代 ADR-019 中“向量降级仍执行同一阈值过滤”的约束。

**排除方案**：
- 自动预检索直接调用 `KnowledgeSearchTool`：Tool 包含 ThreadLocal 范围、参数协议和字符串展示逻辑，会让内部编排反向依赖 LLM 适配层。
- 后端通过 HTTP 调用自身的知识库检索接口：会引入无意义的序列化、认证和网络失败边界，Controller 也不是可复用业务层。
- 对每个知识库分别调用单库搜索再合并：会重复执行 Query Rewrite 和 Rerank，且各库局部 topK 分数无法形成一次全局排序。
- 保留自动预检索与页面搜索两套流水线：阈值、候选数量或重排提供方变更时仍会产生行为漂移。

**理由**：检索策略只有一个权威实现后，模型、阈值和召回策略的调整不会要求同时修改页面、工具和 Agent 三条链路。显式分数来源避免把 Milvus 原始余弦分数误当作 0 到 1 的重排相关度，从而满足“阈值只在模型重排后生效”的语义。

**约束**：工具和自动预检索只能传入当前 Agent 应用关联的完整知识库 ID 集合，不得扩展到用户其他知识库；REST 单库入口必须委托同一多库实现；成功重排结果不得用向量分数补足，否则同一列表会混合两种分数来源；外部重排失败不重试并显式标记为向量降级；Knowledge 页面传入的阈值只对成功重排结果生效。

---

### ADR-022 自动检索结果作为当前轮模型 user 输入的参考资料

**时间**：2026-07

**决策**：Agent 自动预检索命中内容后，将格式化的 `enhancedContext` 放在本轮原始用户问题之前，生成仅供执行模型使用的 `executionUserMessage`。知识资料使用明确边界，并声明其只作为事实参考、不代表用户指令。同步和流式执行统一消费该字段；数据库持久化、标题生成、Hook 参数和审计日志继续使用用户原始消息。知识上下文不再合并到执行器 system prompt，规划器仍通过 `plannerSessionContext` 获取同一份检索结果。

**排除方案**：
- 继续注入 system prompt：SOCIAL 与 TASK 使用不同的 system prompt 组装路径，容易出现检索已完成但动态上下文未进入最终模型请求；外部文档也不应获得 system 级指令优先级。
- 把增强后的消息作为真实 `ChatMessage` 持久化：会让后续轮次重复携带旧检索结果，增加 token 消耗并污染用户原始对话历史。
- 在 `ReactAgent` 内直接调用知识库服务：会让通用执行器依赖 RAG 业务，并使同步、流式和规划阶段更难共享同一检索结果。

**理由**：当前轮 user 输入是所有轮次模式都会经过的稳定入口，能够消除知识注入对轮次分类和 system prompt 分支的依赖；把知识资料与原始问题分段后，模型既能读取检索证据，也能识别真实用户意图。原始消息与执行消息分离，可同时保证历史准确、实现低耦合和单轮检索结果不泄漏到后续会话。

**约束**：没有命中结果时 `executionUserMessage` 必须与原始消息完全一致；知识资料必须位于原始问题之前并标记为不可信参考内容；任何路径都不得把增强后的执行消息写入数据库；同一轮知识上下文只能注入一次；同步与流式路径必须保持一致。

---

### ADR-023 Agent 运行轨迹按用户请求持久化并与聊天展示分离

**时间**：2026-07

**决策**：保留 `chat_message` 作为用户输入、最终回答和前端展示事件的权威数据源；新增 `agent_run`，每次用户请求最多创建一条运行记录，在正常完成、达到最大迭代次数或用户主动中断时一次写入规划、ReAct 工具步骤、最终回答、运行状态和 token 用量。运行轨迹使用显式版本号；工具调用步骤保留协议连续性需要的 `reasoning_content`，普通最终回答的完整推理不写入运行轨迹。后续上下文持久化使用独立的会话级有界快照，不从前端 `events_json` 反推模型协议。

**排除方案**：
- 每个模型消息或工具结果创建一条数据库记录：精确审计能力最强，但当前需求以跨轮上下文为主，极端 200 次迭代会产生大量细粒度记录和事务写入。
- 每个工具步骤更新同一条持续增长的 JSON：行数最少，但会反复重写越来越大的 LONGTEXT，产生明显写放大，并在并发更新时增加覆盖风险。
- 继续只在 `PAUSED_MAX_ITERATIONS` 时写 `checkpoint_json`：只能恢复达到上限的运行，正常完成后的工具证据和未完成事项仍无法进入下一轮上下文。
- 把完整运行轨迹继续塞入 `events_json`：展示事件缺少稳定的模型协议边界，且前端结构变化不应影响模型恢复。

**理由**：一次用户请求一条不可变运行记录把数据库行数控制在与聊天轮数同一数量级，同时避免运行中反复覆盖大 JSON。`AgentStep` 已经保存步骤序号、工具调用、工具结果和 reasoning，可作为第一阶段运行轨迹的结构化载体；聊天展示、运行审计和未来模型上下文各自拥有清晰的数据边界。

**约束**：正常运行不得按工具步骤拆行；运行轨迹只在收尾边界写入，当前阶段不承诺进程突然崩溃时恢复尚未完成的一轮；用户主动中断必须保存中断前已完成步骤；数据库写入或轨迹序列化失败不得静默忽略；未来引入会话上下文快照时必须按 token 容量保留完整工具调用组，并为旧会话保留最近二十条消息的兼容降级路径。

---

### ADR-024 会话上下文采用持久摘要与按 token 保留的完整工具协议

**时间**：2026-07

**决策**：新增每会话一条的 `conversation_context` 可重建快照，保存版本化长期摘要、最近完整模型协议、协议 token 估算和 `last_applied_run_id`。下一轮上下文由“持久摘要 + 按 token 保留的最近完整工具协议 + 当前用户消息”组成；不再把固定最近二十条作为正常路径，仅在旧会话尚无快照时兼容降级。`agent_run` 同时保存 `trace_json` 和可直接重放的 `protocol_json`，工具调用 assistant 消息必须原样保留 `content`、`reasoning_content` 和同轮 `tool_calls`，普通最终答案不长期保存完整推理。

**压缩管线**：L3 先把单个超大工具结果替换为头尾预览，原文仍保留在 `agent_run`；L2 再把超出最近工具结果预算的旧结果替换为可重跑占位符；L1 按 token 从最旧协议开始选择压缩区，切分点必须位于完整 `assistant(tool_calls) + tool results` 组边界；L4 仅在估算 token 超过阈值时调用 LLM，把最旧完整协议合并进持久摘要，并把最近协议收敛到目标 token。运行过程中达到同一阈值也执行压缩，流式前端通过状态事件提示用户。

**排除方案**：
- 固定保留最近若干轮或若干条：工具结果大小差异极大，无法稳定控制上下文容量，也可能拆断工具协议。
- 只保存最终聊天消息：下一轮无法获得上一轮工具调用、工具证据和未完成事项。
- 长期保存所有普通最终推理：存储和隐私成本高，且长期记忆真正需要的是目标、约束、结论、证据、文件变化和待办。
- 把摘要作为单次执行内变量：用户下一次发送消息时会丢失压缩结果，并重新退回有限聊天历史。

**理由**：原始运行账本提供可审计、可补偿的事实来源，会话快照提供低成本读取路径；按 token 而不是按条数控制容量，能够让短消息保留更多、超大工具输出更早压缩。分层逻辑压缩优先避免额外模型调用，只有容量仍超限时才使用 LLM 生成语义摘要。

**约束**：默认摘要阈值为 512000 token、压缩目标为 320000 token、最近完整工具结果预算为 192000 token；token 估算必须包含正文、reasoning 和工具参数并使用安全系数；`conversation_context.session_id` 必须作为显式主键独立写入，不使用 `@MapsId` 或双向一对一派生主键；快照更新失败不得丢失已经落库的 `agent_run`，下一轮必须按 `last_applied_run_id` 自动补偿；清空或删除会话时必须显式删除聊天消息、运行账本和上下文快照。

---

### ADR-025 活动运行由服务端快照与临时事件缓存支持跨设备续看

**时间**：2026-07

**决策**：新增会话级 `ActiveAgentRunService`，在流式任务开始时登记运行 ID、开始时间和当前阶段，并按运行内递增序号临时缓存已有 SSE 用户可见事件。前端查询 `GET /api/sessions/{id}/runs/active` 判断当前运行，再通过 `GET /api/sessions/{id}/runs/active/events?after={sequence}` 增量补回文字、工具调用和工具结果；刷新页面或同一用户在另一设备打开会话时，从序号 0 恢复本轮完整展示过程并继续增量更新。任务完成、错误或中断后移除活动缓存，前端检测到运行消失后重新加载已经持久化的聊天历史。

**排除方案**：
- 只把运行状态写入 `sessionStorage` 或 `localStorage`：只能恢复同一浏览器，无法支持手机和电脑同时查看，也会把服务端事实错误地交给客户端维护。
- 运行中持续覆盖 `agent_run.trace_json`：能提供更完整的崩溃恢复，但会违背 ADR-023 的收尾边界单次写入约束，并对长任务产生明显 JSON 写放大。
- 为补播过程新增细粒度数据库事件表：可以跨服务重启恢复，但会引入高频写入、清理策略和展示事件长期存储成本，超出当前“浏览器刷新与同账号换设备续看”的边界。
- 让新设备重新连接原始启动 SSE：原接口同时承担启动任务，重连会重复发起同一用户请求，不能作为只读订阅接口。

**理由**：刷新后只恢复阶段和耗时无法延续用户正在观察的工作过程。临时事件缓存复用已有 SSE 契约，不改变 `chat_message` 与 `agent_run` 的收尾持久化边界；原始连接继续提供最低延迟的实时流，恢复页面使用事件序号补齐断线期间的缺口。服务端按会话保存事实，使同一用户的不同设备不依赖彼此的浏览器缓存。

**约束**：活动快照和事件只保证当前服务进程存活期间的刷新与跨设备恢复，服务重启后以已经落库的历史和 `agent_run` 为准；同一会话只保留最近一次活动运行；查询接口必须先校验会话归属；心跳不进入补播缓存；恢复页面必须按事件序号去重并复用实时流的同一套渲染逻辑；任务结束后立即回到持久化历史；跨设备停止任务需要独立的服务端取消协议，当前版本不得把本地停止按钮误用于恢复流。

---

### ADR-026 模型原生工具协议在 LLM 适配层归一化

**时间**：2026-07

**决策**：`BaseLlmServiceImpl` 继续以 OpenAI `tool_calls` 作为上层统一协议；当 DeepSeek V4 兼容层把原生 DSML 工具调用错误地放入 `content` 或 `reasoning_content` 时，由单次响应级 `DeepSeekDsmlStreamParser` 在 LLM 适配层识别并恢复为 `LlmToolCall`。可读的思考和过渡文字继续按原字段实时输出，DSML 标签与参数不进入 SSE、聊天历史或运行轨迹。流式解析只暂存可能组成 DSML 标记的最短后缀，支持跨 chunk 和一轮多个工具调用；原生 `tool_calls` 与 DSML 同时存在时优先原生结果，避免重复执行。

**排除方案**：
- 前端正则隐藏 DSML：只能掩盖显示问题，工具不会执行，历史和模型协议仍被污染。
- 通过提示词禁止模型输出 DSML：DSML 是 DeepSeek V4 官方工具协议，问题发生在兼容层归一化缺失，提示词不能形成可靠协议边界。
- 把全部 `content` 缓存到流结束后再统一解析：实现简单，但会破坏现有思考模式的实时反馈。
- 在 `ReactAgent` 中解析 DSML：会让执行器理解具体模型协议，破坏 LLM 适配层与 Agent 业务层的职责边界。

**理由**：DeepSeek、vLLM 和 SGLang 都把模型专用工具语法交给模型协议解析器，再向 Agent 暴露统一结构。把兼容逻辑放在 `BaseLlmServiceImpl` 边界，可以让同步与流式路径共享同一语义，使 `ReactAgent`、工具调度器、SSE 展示和持久化继续只处理稳定的 `LlmToolCall`。

**约束**：解析器必须尊重 DSML `string="true|false"` 的类型语义，拒绝重复参数、非法 JSON、不完整标签和非法工具名；解析失败时不得把原始协议降级为普通文字。DSML 与原生 `tool_calls` 同时出现时不得合并执行；普通文字中仅与标记前缀相似的内容必须在流结束时原样释放；新增协议变体必须通过流式分块、多个调用、类型恢复和失败隔离测试后才能接入。

---

### ADR-027 用户私有 MCP 由 Sandbox 配置并支持单 Server 超时

**时间**：2026-07

**决策**：系统内置 MCP 继续由管理员在 `application.yml` 中配置；用户自行添加的 MCP 统一写入该用户 Sandbox 的 `/home/gem/.mcp/servers.json`。`/mcp` 页面和 Agent 管理工具共享 `McpConfigurationService`，支持 Streamable HTTP 地址、自定义 headers、启停和单 Server `requestTimeoutSeconds`。页面接口按当前登录用户直接定位 Sandbox，不要求先选择聊天会话；列表只返回 Header 名称，不返回 Header 值，编辑时同名 Header 留空表示保留旧值。请求超时只覆盖对应 MCP Client 的单次工具调用，未配置时继承后端全局默认值。

**排除方案**：
- 把墨刀等用户选择的服务加入官方系统 MCP：会把个人 Token 和个人空间错误提升为全局配置，也无法满足多用户隔离。
- 只允许 Agent 手工编辑 `servers.json`：模型容易先判断工具参数不支持 headers，再绕到文件工具；页面也无法提供状态、工具列表和安全的凭据编辑体验。
- 为生成类 MCP 增加独立长任务框架：各服务的推荐等待时间不同，当前需求只需让用户按服务方文档配置超时，不应擅自改变 MCP 调用语义。
- 在 GET 接口回传完整 headers：便于直接编辑，但会让 Token 进入浏览器响应、调试面板和前端内存，扩大凭据暴露面。

**理由**：用户级配置文件与现有运行时 Client 生命周期已经按 userId 隔离，复用同一编排服务能让页面操作、Agent 自动安装和实际工具调用保持一致。单 Server 超时解决生成类工具超过全局 60 秒的问题，又不会改变其他 MCP 的故障恢复速度。Header 值不回显并使用留空保留协议，可以兼顾日常编辑与最小暴露。

**约束**：用户 headers 会以明文保存在其 Sandbox，拥有 Sandbox 文件读取能力的 Agent 仍可能读取，页面必须明确提示该边界；后端日志、列表响应和错误信息不得回显 Header 值；用户动态配置不得覆盖系统 Server ID，不得在 WebAgent 主机启动用户 stdio；请求超时必须限制在 1 到 3600 秒；连接失败时保留配置供用户修正，但不得关闭同名旧可用 Client。
