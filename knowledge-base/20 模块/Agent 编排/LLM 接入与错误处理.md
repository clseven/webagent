---
project: webagent-clean
type: module
status: verified
area:
  - agent
  - llm
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/LlmService.java
  - src/main/java/com/example/sandbox/web/service/impl/BaseLlmServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/DeepSeekLlmServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/ZhipuLlmServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/VisionLlmServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/DeepSeekLlmErrorPolicy.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java
  - src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java
  - src/main/resources/application.yml
updated: 2026-07-09
---

# LLM 接入与错误处理

## 1. 模块概览

本文说明 LLM 服务的抽象设计、Bean 分工、DeepSeek 错误策略和视觉观察链路的实现细节。

当前实现里需要特别注意七点：

- 所有 LLM 厂商走 OpenAI 兼容协议，大量逻辑在 `BaseLlmServiceImpl` 复用，子类只配置地址、Key 和模型名。
- 执行器当前是 DeepSeek 的 `deepseek-v4-flash`，支持原生 tool_calls 和思考模式。
- [[#DeepSeekLlmErrorPolicy]] 区分可重试和不可重试错误：400/401/402/422/429 不重试，500/503 和网络故障最多重试 2 次。
- 错误会传播到 Agent/SSE 层，不会伪装为正常模型回答。
- 视觉模型 Agnes 只用于图片观察，执行器不直接处理图片字节。
- WebClient 连接超时 10 秒，响应超时 300 秒（长任务需要较长时间）。
- 工具调用解析优先原生 tool_calls，回退到 ReAct 文本正则。

## 2. 适用范围

### 2.1 本文覆盖

- `LlmService` 接口和 `BaseLlmServiceImpl` 抽象基类。
- `executorLlm`、`plannerLlm`、`visionLlm` 三个 Bean 的分工和配置。
- DeepSeek 思考模式和 reasoning_effort 配置。
- [[#DeepSeekLlmErrorPolicy]] 的重试策略和错误映射。
- 视觉观察链路。
- Token 统计和传播。

### 2.2 本文不覆盖

- Agent 编排如何调用 LLM，见 [[Agent 编排模块]]。
- PlanAgent 和 ReactAgent 的内部逻辑，见 [[计划与 ReAct 执行]]。
- token 用量的前端展示和 API，见 [[后端 HTTP API]]。

## 3. LLM 抽象层

### 3.1 接口设计

`LlmService` 是统一模型接口，支持：

| 方法 | 用途 |
| --- | --- |
| `chat` | 普通聊天 |
| `chatWithSystem` | 带系统提示的聊天 |
| `chatWithSystemResponse` | 返回完整响应（含 token 用量） |
| `chatWithTools` | 带工具的聊天（ReAct 模式） |
| 流式方法 | 工具调用流式输出 |
| 多模态 | OpenAI vision 格式的多模态 content parts |

### 3.2 BaseLlmServiceImpl

`BaseLlmServiceImpl` 是抽象基类，负责通用逻辑：

- HTTP 请求构建和发送（用 `WebClient`）。
- 响应解析和工具调用解析。
- 流式 chunk 解析。
- Token 用量抽取。
- 错误处理和重试。

子类只需在构造函数里传入 `apiUrl`、`apiKey`、`model` 和错误策略，可选覆盖 `customizeRequestBody` 定制请求体。

### 3.3 HTTP 配置

| 参数 | 值 | 说明 |
| --- | --- | --- |
| 连接超时 | 10 秒（`CONNECT_TIMEOUT_MILLIS`） | TCP 连接建立超时 |
| 响应超时 | 300 秒（`RESPONSE_TIMEOUT_SECONDS`） | 长任务需要较长时间，如多轮 ReAct |
| HTTP 客户端 | Reactor Netty（`WebClient`） | 响应式，支持流式 |

## 4. 当前 Bean 分工

| Bean 名 | 实现类 | 配置来源 | 模型 | 用途 |
| --- | --- | --- | --- | --- |
| `executorLlm` | `DeepSeekLlmServiceImpl` | `agent.llm.executor` | `deepseek-v4-flash` | PlanAgent 和 ReactAgent 主流程 |
| `plannerLlm` | `ZhipuLlmServiceImpl` | `agent.llm.planner` | `deepseek-v4-flash` | 会话标题生成等轻量调用 |
| `visionLlm` | `VisionLlmServiceImpl` | `agent.llm.vision` | `agnes-2.0-flash` | 图片观察 |

注意：`AgentPlannerService` 注入的是 `@Qualifier("executorLlm")`，规划阶段实际复用执行器模型，不是 `plannerLlm`。`plannerLlm` 当前主要用于标题生成。`judgeIntent` 也用 `executorLlm`。

### 4.1 DeepSeek 思考模式

`DeepSeekLlmServiceImpl` 通过 `customizeRequestBody` 为请求体注入两个字段：

| 字段 | 配置项 | 当前值 | 说明 |
| --- | --- | --- | --- |
| `thinking` | `agent.llm.executor.thinking-enabled` | `true`（yml） | 是否启用思考模式 |
| `reasoning_effort` | `agent.llm.executor.reasoning-effort` | `high` | 思考强度，可选 low/medium/high/max |

思考模式让模型在输出答案前先做推理。`reasoning_effort` 控制思考深度，值越高推理越充分但耗时越长。这两个字段只在 DeepSeek 请求中生效，Agnes 不使用。

## 5. DeepSeek 错误策略

[[#DeepSeekLlmErrorPolicy]] 实现了 `LlmErrorPolicy` 接口，区分可重试和不可重试错误。

### 5.1 HTTP 错误映射

| 状态码 | 用户提示 | 是否重试 |
| --- | --- | --- |
| 400 | 请求格式错误，请联系管理员检查请求配置 | 否 |
| 401 | API Key 无效，请联系管理员检查配置 | 否 |
| 402 | 账户余额不足，请充值后重试 | 否 |
| 422 | 请求参数无效，请联系管理员检查模型或参数配置 | 否 |
| 429 | 请求过于频繁，请稍后再试 | 否（避免限流期间增加压力） |
| 500 | 服务暂时异常，请稍后再试 | 是，最多 2 次 |
| 503 | 服务当前繁忙，请稍后再试 | 是，最多 2 次 |
| 其他 | DeepSeek 服务请求失败（HTTP xxx），请稍后再试 | 否 |

### 5.2 重试逻辑

`isRetryable` 判断异常是否可重试：

- `LlmApiException` 且状态码为 500 或 503：可重试。
- `IOException` 或 `TimeoutException`：可重试（网络故障）。
- 其他：不可重试。

`maxRetries` 返回 2，即一次原请求加两次重试。重试用 Reactor 的 `Retry` 机制实现。

### 5.3 finish_reason 处理

`finishReasonError` 把非正常结束原因转换为明确错误：

| finish_reason | 处理 |
| --- | --- |
| `stop` / `tool_calls` | 正常结束，返回 null |
| `length` | 输出达到长度限制，请缩短问题或减少上下文 |
| `content_filter` | 因内容安全限制中止，请调整问题 |
| `insufficient_system_resource` | 推理资源不足，请稍后 |
| 其他 | 返回 null（不视为错误） |

### 5.4 错误传播

`propagateErrors` 返回 true，意味着 DeepSeek 错误会传到 Agent/SSE 层，不会伪装为正常模型回答。`normalize` 把网络异常和未知异常转换为稳定的用户提示 `LlmApiException`。

## 6. 视觉观察链路

执行器不直接处理图片字节，图片观察由 Agnes 视觉模型负责。完整链路：

1. 执行器调用 `view_image` 工具读取图片。
2. `ImageBuffer` 保存图片字节和元数据（path、bytes、mimeType）。
3. `ReactAgentHookService.viewImageHook` 在工具执行后触发（PostToolUseHook）。
4. Hook 从 `ImageBuffer.take(sessionId)` 取出图片数据。
5. Hook 构造 vision 消息（`ChatMessage.userMessageWithImage`），调用 `visionLlm.chatWithSystemResponse`。
6. 视觉模型返回客观观察文本。
7. Hook 把观察结果作为额外 user 消息注入 ReactAgent 消息列表。
8. 主执行模型继续基于文本观察做后续推理和最终回复。

### 6.1 视觉系统提示

视觉模型的系统提示要求：

- 根据图片内容输出客观观察结果。
- 用户没有具体问题时，概括图片主要内容、可见文字、界面状态和明显细节。
- 用户有具体问题时，优先提取与问题相关的信息。
- 看不清或不确定的内容要明确说明，不要猜测。
- 只输出观察结果，不要寒暄。

### 6.2 降级行为

视觉模型失败时的降级：

- 观察结果为空或 blank：用"视觉模型没有返回有效观察结果，主 Agent 不能依赖该图片判断"。
- 调用异常：用"图片已加载，但视觉模型暂时无法分析"加错误信息，不阻断主流程。
- ImageBuffer 没有图片数据：只记 warn 日志，不注入消息。

## 7. 工具调用解析

`BaseLlmServiceImpl` 的工具调用解析分两层：

### 7.1 原生 tool_calls

优先解析 LLM 返回的 `tool_calls` 字段，这是 OpenAI 标准格式。一个响应里可以包含多个 tool_call，收集后可以并发调度。工具名称用 `TOOL_NAME_PATTERN`（`^[a-zA-Z_][a-zA-Z0-9_]*$`）校验合法性，防止 LLM 输出非法字符。

### 7.2 ReAct 文本回退

兼容老模型，用正则匹配 ReAct 文本格式：

- `ACTION_PATTERN`：匹配 `Action: tool_name`。
- `INPUT_PATTERN`：匹配 `Action Input:`。
- `KV_PATTERN`：匹配 `key=value` 格式的参数。

回退只在原生格式不可用时使用，当前 DeepSeek 默认走原生格式。

## 8. Token 统计

`AgentServiceImpl` 和 `AgentPlannerService` 按 userId/sessionId 记录 token 用量，usageType 区分用途：

| usageType | 来源 | 说明 |
| --- | --- | --- |
| `plan` | `AgentPlannerService.plan`（同步） | 规划阶段 |
| `plan_stream` | `AgentPlannerService.plan`（流式） | 规划阶段（流式） |
| `chat` | `AgentServiceImpl`（同步从 AgentResponse，流式从 done 事件） | 执行阶段 |
| `title` | `AgentServiceImpl.scheduleGeneratedTitle` | 标题生成 |

每条记录包含 `promptTokens`、`completionTokens`、`cacheHitTokens`、`totalTokens`。`LlmUsage` 是 record，字段不可变。PlanAgent 的 `mergeUsage` 把首次生成和纠正生成的 token 合并。

前端 `TokenStats` 页面通过 [[后端 HTTP API]] 查看汇总、每日和模型维度统计。

## 9. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| LLM 调用一直重试 | `DeepSeekLlmErrorPolicy.isRetryable`、状态码 | 500/503 最多重试 2 次，其他不重试 |
| API Key 无效 | `agent.llm.executor.api-key`、`DEEPSEEK_API_KEY` | 401 不重试，检查环境变量是否注入 |
| 余额不足 | DeepSeek 账户 | 402 不重试，需要充值 |
| 请求过于频繁 | 429 状态、并发请求 | 429 不重试，避免限流期间增加压力 |
| 输出被截断 | finish_reason=`length` | 减少上下文或缩短问题 |
| 内容安全限制 | finish_reason=`content_filter` | 调整问题措辞 |
| 图片观察为空 | `visionLlm` 配置、`ImageBuffer`、`AGNES_API_KEY` | 视觉模型失败会降级为提示消息 |
| 规划 token 偏高 | `mergeUsage`、repairPrompt | 纠正一次会多一次调用，token 合并 |
| 响应超时 | `RESPONSE_TIMEOUT_SECONDS`（300s）、网络 | 长任务可能接近 300 秒上限 |
| 工具调用解析失败 | LLM 返回格式、tool_calls 字段 | 检查模型是否支持 function calling |

## 10. 术语速查

本节用于 Obsidian 悬浮预览。阅读正文时，把鼠标停在带链接的术语上，可以快速看到这里的释义。

### BaseLlmServiceImpl

`BaseLlmServiceImpl` 是 LLM 服务抽象基类，提供 OpenAI 兼容 API 的通用实现。不同厂商（DeepSeek、智谱等）的 API 都遵循 OpenAI 协议，请求构建、响应解析、工具调用解析、流式 chunk 解析和 token 用量抽取等逻辑在此复用。子类只需配置 apiUrl、apiKey、model 和错误策略。

### executorLlm

`executorLlm` 是执行器 LLM Bean，当前实现为 `DeepSeekLlmServiceImpl`，读取 `agent.llm.executor` 配置，模型 `deepseek-v4-flash`。负责 PlanAgent 规划和 ReactAgent 执行主流程，支持思考模式和 reasoning_effort。

### plannerLlm

`plannerLlm` 是轻量规划 LLM Bean，当前实现为 `ZhipuLlmServiceImpl`，读取 `agent.llm.planner` 配置。当前主要用于会话标题生成，规划阶段实际用的是 `executorLlm`。

### visionLlm

`visionLlm` 是视觉模型 Bean，实现为 `VisionLlmServiceImpl`，读取 `agent.llm.vision` 配置，模型 `agnes-2.0-flash`。只在图片观察 Hook 中使用，不参与规划和执行主流程。

### DeepSeekLlmErrorPolicy

`DeepSeekLlmErrorPolicy` 是 DeepSeek 执行器专属错误策略。400/401/402/422/429 不重试，500/503 和网络故障最多重试 2 次。错误传播到 Agent/SSE 层，不伪装为正常回答。`finishReasonError` 把 length/content_filter/insufficient_system_resource 转换为用户可理解的提示。

### LlmErrorPolicy

`LlmErrorPolicy` 是错误策略接口，定义 `httpError`、`isRetryable`、`maxRetries`、`propagateErrors`、`normalize`、`finishReasonError` 等方法。默认实现 `LlmErrorPolicy.DEFAULT` 不改变现有行为，DeepSeek 有专属实现。

### ImageBuffer

`ImageBuffer` 是图片数据缓冲区，`view_image` 工具执行后把图片字节和元数据存入，视觉观察 Hook 再取出。按 sessionId 隔离，`take` 操作取出后即移除。Hook 取不到数据时只记 warn 不注入消息。

### LlmUsage

`LlmUsage` 是 token 用量 record，包含 `promptTokens`、`completionTokens`、`totalTokens`、`cacheHitTokens`。字段不可变，`mergeUsage` 把多次调用的用量相加合并。

### LlmApiException

`LlmApiException` 是 LLM 调用异常，携带 HTTP 状态码和用户可理解的中文提示。`propagateErrors=true` 时会传播到 SSE 层，前端能看到具体错误原因。

## 11. 相关页面

[[Agent 编排模块]] · [[计划与 ReAct 执行]] · [[工具系统模块]] · [[运行配置]]