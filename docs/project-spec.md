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
