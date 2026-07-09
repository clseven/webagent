---
project: webagent-clean
type: module
status: verified
area:
  - agent
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentTurnContextService.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentPlannerService.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgentFactory.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java
  - src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java
  - src/main/resources/application.yml
updated: 2026-07-09
---

# Agent 编排模块

## 1. 模块概览

本文说明一次用户消息从进入系统到返回回答的完整编排链路：归属校验 → 历史加载 → 上下文准备 → 意图分类 → 规划 → ReAct 执行 → 保存结果 → 记录用量。

当前实现里需要特别注意六点：

- 规划和执行分属两个阶段，但当前共用同一个执行器模型 `deepseek-v4-flash`；图片观察由 Agnes 作为 [[#visionLlm]] 处理。
- 每轮对话先由 [[#TurnMode]] 分类（SOCIAL/TASK/AMBIGUOUS），SOCIAL 轮次跳过工具、工作区、知识库和 StopHook，走轻量路径。
- 同步入口 `chat` 和流式入口 `chatStream` 共享同一套上下文准备和执行器创建逻辑，区别只在事件回传方式。
- 新增 Hook 时必须同时覆盖同步和流式两条路径，`ReactAgentFactory` 的两个 create 方法各自装配一次。
- token 用量按阶段分别记录：planner 记 `plan`/`plan_stream`，executor 记 `chat`，标题生成记 `planner`/`title`。
- 工具运行态在同步路径的 `finally` 和流式路径的 `doOnComplete`/`doOnError` 里清理，否则后续会话可能串上下文。

## 2. 适用范围

### 2.1 本文覆盖

- `AgentServiceImpl` 的会话创建、删除、同步和流式对话入口。
- `AgentTurnContextService` 的单轮上下文准备全流程。
- `AgentPlannerService` 的规划阶段和意图判断。
- `ReactAgentFactory` 的同步/流式执行器创建。
- `ReactAgentHookService` 的 Hook 注册顺序和开关。
- `AgentTurnContext`、[[#TurnPolicy]]、[[#TurnMode]] 的字段含义和影响。
- LLM、Hook 和子代理相关配置的来源和默认值。

### 2.2 本文不覆盖

- `ReactAgent` 内部 ReAct 循环和 function calling 细节，见 [[计划与 ReAct 执行]]。
- LLM 客户端的错误处理和重试策略，见 [[LLM 接入与错误处理]]。
- 工具如何被调用、参数如何校验，见 [[Agent 工具调用]]。
- 沙箱创建和生命周期，见 [[Sandbox 模块]]。
- 知识库上下文如何注入，见 [[RAG 知识库模块]]。

## 3. 当前配置总览

### 3.1 LLM 配置

| 配置项 | 当前值 | 环境变量 | 说明 |
| --- | --- | --- | --- |
| `agent.llm.planner.api-url` | `https://api.deepseek.com` | `DEEPSEEK_LLM_URL` | 规划器模型地址 |
| `agent.llm.planner.api-key` | 空 | `DEEPSEEK_API_KEY` | 规划器 API Key |
| `agent.llm.planner.model` | `deepseek-v4-flash` | `DEEPSEEK_LLM_MODEL` | 规划器模型名 |
| `agent.llm.executor.api-url` | `https://api.deepseek.com` | `DEEPSEEK_LLM_URL` | 执行器模型地址 |
| `agent.llm.executor.api-key` | 空 | `DEEPSEEK_API_KEY` | 执行器 API Key |
| `agent.llm.executor.model` | `deepseek-v4-flash` | `DEEPSEEK_LLM_MODEL` | 执行器模型名 |
| `agent.llm.executor.thinking-enabled` | `true`（yml） / `false`（代码默认） | `DEEPSEEK_THINKING_ENABLED` | 执行器是否启用思考模式 |
| `agent.llm.executor.reasoning-effort` | `high` | 无 | 思考强度，仅 DeepSeek 有效 |
| `agent.llm.vision.api-url` | `https://apihub.agnes-ai.com/v1` | `AGNES_LLM_URL` | 视觉模型地址 |
| `agent.llm.vision.api-key` | 空 | `AGNES_API_KEY` | 视觉模型 API Key |
| `agent.llm.vision.model` | `agnes-2.0-flash` | `AGNES_LLM_MODEL` | 视觉模型名 |

注意：规划器和执行器当前共用同一组 DeepSeek 配置，`AgentPlannerService` 注入的是 `@Qualifier("executorLlm")`。标题生成用的是 `@Qualifier("plannerLlm")`。

### 3.2 Hook 与执行控制配置

| 配置项 | 当前值 | 环境变量 | 说明 |
| --- | --- | --- | --- |
| `agent.hook.state-check-enabled` | `true` | 无 | 是否启用文件状态检查 Hook；出问题可置 `false` 立即恢复无校验 |
| `agent.hook.concurrent-tool-execution-enabled` | `true` | 无 | 是否启用工具并发执行；READ 类并发、WRITE/EXCLUSIVE 串行 |

这两个配置在 `AgentConfigProperties.Hook` 中定义，`ReactAgentHookService` 读取后决定是否注册 `FileStateCheckHook` 和是否打开 `setConcurrentToolExecutionEnabled`。当前 `application.yml` 没有显式写出 `agent.hook` 段，使用代码默认值。

### 3.3 子代理与后台任务配置

| 配置项 | 当前值 | 环境变量 | 说明 |
| --- | --- | --- | --- |
| `agent.sub-agent.enabled` | `true` | `SUB_AGENT_ENABLED` | 是否启用 `run_subagent` 工具 |
| `agent.sub-agent.timeout-seconds` | `120` | `SUB_AGENT_TIMEOUT` | 单个子代理超时 |
| `agent.background.max-concurrent` | `5` | `BG_MAX_CONCURRENT` | 后台任务最大并发数 |

子代理类型分 `analyzer`（10 轮）、`searcher`（5 轮）、`browser`（10 轮）、`general`（15 轮），每种可单独开关和设最大迭代数。

## 4. 入口接口与请求参数

### 4.1 同步对话

入口方法：`AgentServiceImpl.chat`

```text
POST /api/sessions/{sessionId}/chat
```

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | `String` | 是 | 目标会话 ID |
| `message` | `String` | 是 | 用户消息 |

返回完整 `ChatMessage`，含 `response`、`reasoning` 和 `events`。同步路径在执行完成后一次性返回，适合非流式调用和后台调用。

### 4.2 流式对话

入口方法：`AgentServiceImpl.chatStream`

```text
POST /api/sessions/{sessionId}/chat/stream
```

返回 `Flux<SseEvent>`，事件类型包括 `plan`、`thinking`、`tool`、`observation`、`answer`、`done`、`error`、`interrupted`。前端按事件实时渲染计划、思考、工具调用、视觉观察和最终答案。

### 4.3 会话创建与删除

`createSession` 和 `createSession(appId)` 会异步创建沙箱，不阻塞 HTTP 响应。沙箱是用户级资源，删除单个会话只移除会话数据，不销毁用户沙箱。批量删除会先去空值和重复项，不属于当前用户的 ID 统一作为跳过项返回。

## 5. 单轮上下文准备

入口方法：`AgentTurnContextService.prepare`

同步和流式入口都调用同一个 `prepare` 方法，确保两条路径的上下文装配完全一致。处理步骤：

1. 加载最近 20 条历史消息。
2. 调用 `judgeIntent` 分类本轮意图，产出 [[#TurnMode]]（SOCIAL/TASK/AMBIGUOUS）。
3. 由 [[#TurnPolicy]]`.forMode(mode)` 映射为本轮策略开关。
4. 判断是否跳过规划：用户开关 `UserContext.isPlanningEnabled()` 且未被轻量路由命中。
5. 确保沙箱就绪，首次访问时同步创建。
6. 记录是否首轮 `firstTurn = history.isEmpty()`。
7. 用户消息入库。
8. 提取文件上下文：用户消息含 `【上传的文件】` 时拼出文件清单提示。
9. 根据 policy 决定是否注入技能提示、工作区目录记忆、知识库增强。
10. 加载 Agent 应用，构建知识库描述。
11. 根据 policy 决定是否构建工具上下文（SOCIAL 轮次用 `AgentToolContext.empty()`）。
12. 汇总 `systemPrompt`，拼接顺序：文件上下文 → 知识库增强 → 工作区记忆 → 技能提示。
13. 构建规划器会话上下文和规划技能元数据。
14. 返回 [[#AgentTurnContext]]。

关键约束：

- `systemPrompt` 的拼接顺序固定，先注入文件上下文，再注入知识库增强，最后才是工作区记忆和技能提示。排查"模型没有看到某段上下文"时要按这个顺序核对。
- 社交轮次（SOCIAL）下，工具、工作区、知识库和 StopHook 全部跳过，`toolContext` 为空，系统提示只剩技能和工作区的空串。
- 工作区目录记忆注入失败只记日志不抛异常，降级为空串。

## 6. 规划阶段

入口方法：`AgentPlannerService.plan`

规划阶段根据 policy 决定是否运行。当 `!UserContext.isPlanningEnabled()` 或 `!policy.shouldPlan()` 时直接跳过，返回 `null`。

运行规划时：

1. 用 [[#AgentTurnContext]] 里的工具定义、规划技能和规划会话上下文构建 `PlanAgent`。
2. 调用 `PlanAgent.plan` 产出 `PlanResult`，含 `plan` 文本和 token 用量。
3. 记录规划阶段 token，usageType 为 `plan`（同步）或 `plan_stream`（流式）。
4. 返回规划文本。

意图判断 `judgeIntent` 复用执行器模型但不带工具/技能/工作区，输出 SOCIAL/TASK，判断失败降级为 TASK。这个判断本身也会消耗一次 LLM 调用，但没有单独记录 token（走的是 `PlanAgent.judgeIntent` 静态方法）。

## 7. 执行器创建与 Hook 装配

### 7.1 执行器创建

入口方法：`ReactAgentFactory.createForChat` / `createForStream`

当 policy 为 SOCIAL 时，创建无工具的 `ReactAgent`，使用 `ReactPromptAssembler.assembleSocial()` 系统提示，`setUseRawSystemPrompt(true)`，不传 plan。

当 policy 为 TASK/AMBIGUOUS 时，使用 `context.toolContext().filteredTools()`、`context.systemPrompt()` 和 plan 构建全能力执行器。

### 7.2 Hook 注册顺序

入口方法：`ReactAgentHookService.configureForChat` / `configureForStream`

同步和流式路径的 Hook 注册顺序：

1. `PreToolUseHook`：`AgentHookExamples.logHook()`（日志 Hook）
2. `PreToolUseHook` + `PostToolUseHook`：`FileStateCheckHook`（受 `agent.hook.state-check-enabled` 控制）
3. `PostToolUseHook`：`viewImageHook`（图片观察，调用 [[#visionLlm]]）
4. `PostToolUseHook`：`AgentHookExamples.largeOutputHook()`（仅流式路径注册）
5. `StopHook`：`FinalTodoGuardHook`（受 `policy.shouldEnableStopHook()` 控制）
6. `setConcurrentToolExecutionEnabled`（受 `agent.hook.concurrent-tool-execution-enabled` 控制）
7. `wireSubAgentParent`：把 `RunSubagentTool` 的父 Agent 指向当前 `ReactAgent`

关键约束：

- 同步和流式各自装配一次，漏掉一条路径会导致行为不一致。
- `FinalTodoGuardHook` 只在 `policy.shouldEnableStopHook()` 为 true 且 sessionId 非空时注册；SOCIAL 轮次不注册。
- `viewImageHook` 在 `view_image` 工具执行后从 `ImageBuffer` 取图片，调用视觉模型生成观察结果；视觉模型失败时降级为"图片已加载但无法分析"的提示消息，不阻断主流程。
- `FileStateCheckHook` 用于防止 TOCTOU，并发落地后尤其重要；关闭后退化为无校验。

## 8. 同步与流式差异

| 维度 | 同步 `chat` | 流式 `chatStream` |
| --- | --- | --- |
| 规划日志标签 | `【规划结果】`，预览 300 字 | `【Stream 规划结果】`，预览 200 字 |
| 规划事件 | 不单独发 | 发 `SseEvent.plan(plan)` |
| 执行入口 | `reactAgent.run` | `reactAgent.runStream` |
| token 记录 | 从 `AgentResponse.getTotalUsage()` 一次记录 | 从 `done` 事件解析 `tokenUsage` 记录 |
| 消息保存 | `conversationService.addAssistantMessage` | 流式执行器内部保存 |
| 标题生成 | 同步路径在保存后调度 | 流式路径在 `done` 事件里调度 |
| 上下文清理 | `finally` 块清理 | `doOnComplete` 和 `doOnError` 清理 |
| 中断检测 | 无 | `sink.isCancelled()` 检查，规划后可能发 `interrupted` |

流式路径的异常处理更复杂：`runStream` 的 `doOnError` 发 `error` 事件并清理上下文，外层 try-catch 也兜底清理。如果 `contextRef` 还没设置就异常，则跳过清理。

## 9. 数据落库

### 9.1 消息

| 数据 | 含义 | 写入时机 |
| --- | --- | --- |
| 用户消息 | 本轮用户输入 | `prepare` 阶段，上下文准备时 |
| 助手消息 | 最终回答 + 思考链 + 事件列表 | 同步路径执行完成后；流式路径由执行器内部保存 |

助手消息的 `events` 字段由 `AgentEventMapper.fromPlanAndSteps` 从规划文本和执行步骤生成，前端用它渲染计划、工具调用和观察的完整时间线。

### 9.2 Token 用量

| 阶段 | usageType | 触发条件 |
| --- | --- | --- |
| 规划 | `plan` / `plan_stream` | `AgentPlannerService.plan` 返回规划后 |
| 执行 | `chat` | 同步路径从 `AgentResponse`；流式路径从 `done` 事件 |
| 标题生成 | `title` | 首轮对话完成后异步触发 |

每条记录包含 `promptTokens`、`completionTokens`、`cacheHitTokens`、`totalTokens`，按 userId 和 sessionId 归属。流式路径解析 `tokenUsage` 失败只记日志，不阻断响应。

### 9.3 标题生成

首轮对话完成后异步生成会话标题。流程：

1. 截断用户消息到 600 字符、助手回复到 900 字符。
2. 用 `plannerLlm` 调用模型，系统提示要求生成不超过 24 字符的中文短标题。
3. 清洗返回：去掉 `title:` 前缀、引号、书名号；过滤"抱歉""错误"等异常开头。
4. 超过 24 字符则截断。
5. 成功后 `updateGeneratedTitle`，失败只记日志，会话保留默认标题。
6. 记录标题生成的 token 用量，usageType 为 `title`。

## 10. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| 规划为空或跑偏 | `AgentPlannerService` 输入、应用配置、历史消息 | SOCIAL 轮次会跳过规划；轻量路由也会跳过 |
| 工具没有出现 | `AgentTurnContextService` 工具列表、MCP 配置、工具过滤、policy.shouldGiveTools | SOCIAL 轮次不注入工具；MCP 未启用时工具变少 |
| 同步和流式表现不一致 | `ReactAgentFactory` 和 `ReactAgentHookService` 两条路径是否都接入 | 流式多了 `largeOutputHook`，其他应一致 |
| 最终回答被反复拦截 | `TodoState`、`FinalTodoGuardHook`、验证字段 | StopHook 只在 shouldEnableStopHook 为 true 时注册 |
| 图片观察没有结果 | `view_image` 工具、Post Hook、`visionLlm` 配置、`ImageBuffer` | 视觉模型失败会降级为提示消息，不报错 |
| 后续会话串了上下文 | `AgentToolContextService.clearRuntimeState` 是否在 finally/doOnComplete 里调用 | 同步看 finally，流式看 doOnComplete 和 doOnError |
| 标题未生成 | 是否首轮、助手回复是否为空、plannerLlm 是否可用 | 标题生成失败只记日志，不影响会话 |
| token 统计缺失 | `TokenUsageService`、usageType 区分、流式 `done` 事件解析 | 规划跳过时不会有 planner 记录 |

## 11. 扩展建议

1. 新增 Hook 时，在 `ReactAgentHookService` 的 `configureForChat` 和 `configureForStream` 同时注册，避免两条路径行为分裂。
2. 新增需要 policy 控制的能力时，在 `TurnPolicy` 增加开关字段，在 `forMode` 里给 SOCIAL 和 TASK 分别赋值，在 `prepare` 里消费，不要在执行器里硬编码。
3. 规划器如果需要切换独立模型，把 `AgentPlannerService` 的 `@Qualifier("executorLlm")` 改成 `@Qualifier("plannerLlm")`，但要注意 `judgeIntent` 当前也用执行器模型。
4. 流式路径如果需要在规划后做条件中断，参考现有 `sink.isCancelled()` 检查模式，不要在异步回调里直接抛异常。
5. 子代理迭代数和超时调整走 `agent.sub-agent.types.*.max-iterations` 和 `agent.sub-agent.timeout-seconds`，不要硬编码。

## 12. 术语速查

本节用于 Obsidian 悬浮预览。阅读正文时，把鼠标停在带链接的术语上，可以快速看到这里的释义。

### AgentTurnContext

`AgentTurnContext` 是单轮 Agent 对话的上下文聚合对象，是一个 record。它把历史、用户消息、是否首轮、策略开关、用户 ID、应用、系统提示、工作区记忆、知识库增强、规划技能和工具上下文统一传给规划和执行阶段。同步和流式入口共用它，避免重复准备。

### TurnPolicy

`TurnPolicy` 是单轮对话的策略开关，控制本轮是否注入规划、工具、技能、工作区、知识库和 StopHook。它由 `TurnPolicyResolver` 根据 `TurnMode` 产出。SOCIAL 对应 LITE（全 false），TASK 和 AMBIGUOUS 对应 FULL（全 true）。

### TurnMode

`TurnMode` 是本轮用户消息的任务类型分类，有三个值：SOCIAL 表示纯社交（打招呼、感谢、告别），TASK 表示明确任务，AMBIGUOUS 表示无法判断并保守按 TASK 处理。由 `PlanAgent.judgeIntent` 产出。

### visionLlm

`visionLlm` 是图片观察使用的视觉模型，当前配置为 Agnes 的 `agnes-2.0-flash`。当 `view_image` 工具执行后，Post Hook 会从 ImageBuffer 取出图片，调用 visionLlm 生成客观观察结果，再作为消息注入主 Agent 的后续上下文。失败时降级为提示消息。

### PlanAgent

`PlanAgent` 是规划阶段的核心类，接收工具定义、规划技能和会话上下文，产出 `PlanResult`（含规划文本和 token 用量）。它还提供 `judgeIntent` 静态方法用于意图分类。

### ReactAgent

`ReactAgent` 是执行阶段的 ReAct 循环执行器，负责 function calling 和工具调用循环。它不负责会话持久化，由 `ReactAgentFactory` 创建并装配 Hook 后使用。

### Hook

Hook 是执行循环中的扩展点，分 PreToolUseHook、PostToolUseHook 和 StopHook 三类。当前用于日志、文件状态检查、图片观察、大输出处理、最终 TodoState 门禁等场景。

### TodoState

`TodoState` 是 Agent 执行过程中的任务看板，记录 todo 的状态、验证和阻塞原因。`FinalTodoGuardHook` 作为 StopHook 检查是否还有未闭环的 todo，防止多步任务未完成就结束。

### FileStateCheckHook

`FileStateCheckHook` 是文件状态检查 Hook，同时注册为 Pre 和 Post。它防止 TOCTOU（检查和使用之间文件状态变化），并发工具执行落地后尤其重要。受 `agent.hook.state-check-enabled` 控制，关闭后退化为无校验。

### LightweightChatRouter

`LightweightChatRouter` 是轻量路由判断器，`shouldSkipPlanning` 决定是否跳过规划。它和用户开关 `UserContext.isPlanningEnabled()` 同时为真时才跳过规划。

### RunSubagentTool

`RunSubagentTool` 是子代理工具，通过 `wireSubAgentParent` 把父 `ReactAgent` 注入进去，让子代理能 fork 出受限工具集执行子任务。可同步或后台运行，受 `agent.sub-agent.enabled` 控制。

### plannerLlm

`plannerLlm` 是轻量规划 LLM，当前主要用于会话标题生成。`AgentPlannerService` 的规划阶段实际用的是 `executorLlm`，不是 `plannerLlm`。

### executorLlm

`executorLlm` 是执行器 LLM，当前配置为 DeepSeek 的 `deepseek-v4-flash`，同时被规划阶段和执行阶段复用。支持思考模式（`thinking-enabled`）和思考强度（`reasoning-effort`）。

## 13. 相关页面

[[计划与 ReAct 执行]] · [[LLM 接入与错误处理]] · [[Agent 工具调用]] · [[工具系统模块]] · [[创建会话并完成一次对话]]