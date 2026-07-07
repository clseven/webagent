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

**决策**：删除基于硬规则的 `AgentSearchPolicyHook`（Pre Hook），把“该不该继续/该不该收尾”的判断挪到 Stop Hook 让模型基于证据自判。`FinalTodoGuardHook` 升级为两层：先看 TodoState 前置硬信号（未闭环/缺证据直接拦），干净后注入证据自检提示（B 方案，harness 不解析模型输出，只靠模型行为——继续调工具则循环继续、给答案则放行）。收敛由 `ReactAgent` 持有的“收尾尝试计数”控制：模型准备收尾时 +1、实际调工具时归零，连续 `MAX_SELF_CHECK_ROUNDS`(=2) 次仍想收尾则第 3 次强制放行；另一端由现有 `MAX_ITERATIONS` 兜底。

**排除方案**：
- 保留 `AgentSearchPolicyHook` 的方向闸/预算闸：不看证据，只按搜索词正则和写死次数上限判断，简单任务被多搜、复杂任务被掐断；方向判断靠英文标识符正则，对纯中文请求失效；且只约束搜索，读文件/跑命令等不受治理，不一致。
- 为搜索单造一套编排：搜索只是工具之一，不该有特殊待遇，靠通用 `ReactAgent` 循环消化发散即可。
- harness 解析模型自检输出再判定：增加脆弱的文本解析耦合；B 方案靠模型二选一行动，harness 只注入提示、不解析标记，更稳。
- 计数器放在 hook 实例里：hook 感知不到“模型调了工具”这个发生在主循环里的事件；故状态归 `ReactAgent`（选项 C），hook 只读传入的计数。

**理由**：“该不该收尾”本质是需要看证据才能判断的决策，硬塞进 Pre Hook 用正则常量干只是创可贴。移到 Stop Hook 后，方向判断由自检第 1 条（模型拆解子问题自然发现跑题）接管，预算由拦截计数 + `MAX_ITERATIONS` 接管，搜索不再有专属逻辑——读文件、跑命令、搜索一视同仁接受证据验收。不新增 Agent、不新增循环，复用现有 Stop Hook 机制。

**约束**：`ReactAgent` 由工厂按会话新建、一个实例只跑一条路径（`run` 或 `runStream`），故收尾计数用实例级字段即可，天然按会话隔离；`StopHook` 接口签名带 `finalizeAttempt` 入参（状态归执行器、hook 只读不持有）；自检提示要求“在思考中完成”，避免污染最终答案；本方案为三方案（Stop Hook 自检 / State Checks / 并发执行）中的第一批，后两者独立立项、分批上线。

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
