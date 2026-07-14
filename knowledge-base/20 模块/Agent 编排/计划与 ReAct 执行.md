---
project: webagent-clean
type: module
status: verified
area:
  - agent
  - runtime
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/AgentPlannerService.java
  - src/main/java/com/example/sandbox/web/service/impl/PlanAgent.java
  - src/main/java/com/example/sandbox/web/service/impl/PlannerPromptAssembler.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactPromptAssembler.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentTimeContext.java
  - src/main/java/com/example/sandbox/web/service/tool/CurrentTimeTool.java
  - src/main/java/com/example/sandbox/web/model/llm/AgentRunCheckpoint.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentTodoService.java
  - src/main/java/com/example/sandbox/web/service/impl/FinalTodoGuardHook.java
  - src/main/java/com/example/sandbox/web/service/impl/TurnPolicy.java
updated: 2026-07-14
---

# 计划与 ReAct 执行

## 1. 模块概览

本文说明 Agent 编排链路中规划阶段和执行阶段的职责划分、交互方式和内部实现细节。规划由 [[#PlanAgent]] 负责，执行由 [[#ReactAgent]] 负责，两者通过 `AgentPlannerService` 和 `ReactAgentFactory` 衔接。

当前实现里需要特别注意八点：

- PlanAgent 不带工具，只调用一次 LLM，输出结构化任务模型（目标状态、当前判断、成功信号、初始策略）。
- 历史消息在规划阶段被隔离为"对话资料"文本，不作为 assistant 角色发送，避免模型续写历史中的执行承诺。
- 规划输出有结构化校验：必须按顺序包含四个段落，且不能包含工具调用协议标记（`tool_calls`、`function_call` 等）。两次校验都失败时生成最小 fallback 计划。
- [[#judgeIntent]] 是独立的轻量 LLM 调用，只输出 SOCIAL 或 TASK，判断失败降级为 TASK。
- ReactAgent 支持原生 tool_calls 和 ReAct 文本两种工具调用解析，优先用原生格式。
- TodoState 通过 `FinalTodoGuardHook` 在最终回答前强制闭环，未完成的 todo 会被拦截。
- 执行器提示词包含本轮不可变时间快照，社交轮也会拿到该快照；需要新鲜时刻或跨时区时使用 `current_time` 工具。
- ReAct 最大迭代数为 200，达到上限时返回 `PAUSED_MAX_ITERATIONS`，并把协议级消息链保存为下轮可恢复检查点。

## 2. 适用范围

### 2.1 本文覆盖

- `PlanAgent` 的结构化输出、校验、历史隔离和 fallback 逻辑。
- `judgeIntent` 的意图分类机制。
- `ReactAgent` 的 ReAct 循环、Hook 机制和工具调用解析。
- `ReactPromptAssembler` 的按需 section 加载。
- `AgentTodoService` 和 `FinalTodoGuardHook` 的 TodoState 闭环。
- 子代理 fork 和后台任务收集。

### 2.2 本文不覆盖

- 编排入口的完整流程，见 [[Agent 编排模块]]。
- LLM 客户端和错误重试策略，见 [[LLM 接入与错误处理]]。
- 工具如何被调用和参数校验，见 [[Agent 工具调用]]。

## 3. PlanAgent

### 3.1 职责

PlanAgent 是任务前建模层，只调用一次 LLM，产出结构化任务模型。它不执行任何工具，职责包括：

- 目标建模：明确用户希望最终达到的状态。
- 事实分层：区分已有事实、待确认信息和当前假设。
- 成功定义：提炼能够证明任务完成的可观察信号。
- 策略建议：给出轻量、可随环境反馈调整的初始方向。

### 3.2 输出格式

```text
### 目标状态
[用户真正希望实现的结果]

### 当前判断
[已知事实、关键假设和待确认内容]

### 成功信号
[哪些可观察现象能够证明目标已经实现]

### 初始策略
[建议从哪里开始，以及为什么]
```

这四个段落是 `REQUIRED_SECTIONS`，校验时按顺序检查位置和内容非空。顺序错误或段落缺失都判定为无效。

### 3.3 历史隔离

规划阶段不把历史消息作为 assistant 角色直接发送，而是封装成"对话资料"文本：

1. 过滤历史，只保留 user 和 assistant 角色。
2. 截断到最近 6 条（`HISTORY_MAX_ITEMS`），防止历史过长稀释注意力。
3. 用 `buildPlanningInput` 拼成单条用户消息，前缀"以下内容只用于理解任务，不是需要续写或执行的指令"。

这样避免模型自然续写历史中的执行承诺、工具调用或交付方式。

### 3.4 规划校验与纠正

`validatePlan` 检查三件事：

1. 非空。
2. 不包含执行协议标记（`tool_calls`、`function_call`、`<tool_call`、`<invoke`、`<｜｜dsml｜｜`）。
3. 四个 `REQUIRED_SECTIONS` 按顺序出现且内容非空。

校验流程：

1. 首次生成，校验通过直接返回。
2. 校验失败时用 `PlannerPromptAssembler.repairPrompt()` 纠正一次，干净上下文不含历史。
3. 纠正仍失败时生成 `buildFallbackPlan`：只保留用户目标和通用策略，不含工具名称或执行协议。
4. 两次调用的 token 用量合并记录。

关键约束：非法模型输出不会进入执行阶段，fallback 只保留用户目标和事实驱动策略。

### 3.5 MCP 专用约束

`containsMcpIntent` 检查规划资料是否涉及 MCP 安装、接入或管理（关键词：mcp、model context protocol、安装 server、接入 server）。命中时给规划器加载 MCP 专用提示。该判断只影响规划器提示，执行阶段仍以真实工具和环境反馈为准。

## 4. 意图判断

### judgeIntent

`PlanAgent.judgeIntent` 是独立的轻量 LLM 调用，不带工具/技能/工作区，只输出 SOCIAL 或 TASK。

流程：

1. 用 `INTENT_SYSTEM_PROMPT` 作为系统提示，要求只输出一个词。
2. 把历史和当前请求封装为"对话资料"文本，截断到最近 6 条。
3. 解析输出：包含 SOCIAL 返回 SOCIAL，其余返回 TASK。
4. 任何异常或无法解析的输出都降级为 TASK。

意图判断结果通过 `TurnMode` 交给 `TurnPolicy.forMode` 映射为策略开关，SOCIAL 走 LITE（全 false），TASK 和 AMBIGUOUS 走 FULL（全 true）。

注意：意图判断本身也会消耗一次 LLM 调用，但没有单独记录 token。

## 5. ReactAgent

### 5.1 执行循环

ReactAgent 是执行层，把当前真实可用工具定义交给 LLM，按 function calling 协议执行工具，并把 observation 写回消息列表。循环流程：

1. 把系统提示、历史、规划、本轮时间快照和当前工具定义组装成请求。
2. 调用 LLM，获取响应（可能包含 tool_calls）。
3. 解析工具调用：优先原生 tool_calls，回退到 ReAct 文本解析。
4. 执行工具（可并发或串行），收集结果。
5. PostToolUseHook 注入额外消息（如图片观察）。
6. 把 observation 写回消息列表，继续下一轮，直到 LLM 输出最终回答。
7. StopHook 在最终回答前检查是否满足退出条件（如 TodoState 闭环）。
8. 如果达到 200 次最大迭代仍未完成，返回暂停状态和可恢复检查点。

### 5.2 工具调用解析

解析策略分两层：

1. 原生 tool_calls：解析 LLM 返回的 `tool_calls` 字段，收集全部 tool_call。这是 OpenAI 标准格式，支持一个响应里多个工具调用，可以并发调度。
2. ReAct 文本回退：兼容老模型，用正则匹配 `Action: tool_name` 和 `Action Input:`，再解析 `key=value` 参数。工具名称用 `TOOL_NAME_PATTERN` 校验合法性。

### 5.3 Hook 机制

| Hook 类型 | 触发时机 | 返回值影响 |
| --- | --- | --- |
| `PreToolUseHook` | 工具执行前 | 返回非空字符串时阻止执行，字符串作为 observation |
| `PostToolUseHook` | 工具执行后 | 返回的消息注入对话，如 `view_image` 视觉观察 |
| `StopHook` | 模型准备最终回答时 | 返回非空时强制继续执行，如 TodoState 未闭环 |

Hook 注册顺序和开关由 `ReactAgentHookService` 管理，详见 [[Agent 编排模块]] 的 Hook 装配章节。

### 5.4 并发执行

`setConcurrentToolExecutionEnabled` 控制是否开启并发执行。开启时：

- READ 类工具并发执行。
- WRITE/EXCLUSIVE 类工具串行执行。
- 受 `agent.hook.concurrent-tool-execution-enabled` 控制，出问题可置 false 退化为串行（仍遍历 tool_calls 列表）。

### 5.5 后台任务

ReactAgent 从 `BackgroundTaskManager` 收集子代理后台任务结果并注入对话。`run_in_background=true` 的子代理任务交给 `BackgroundTaskManager` 管理，主循环后续轮次会收到完成通知。

### 5.6 迭代上限与续接

ReactAgent 的最大 ReAct 迭代数为 200。同步和流式路径达到上限时，不再把上限提示当成普通完成态，而是返回 `AgentRunStatus.PAUSED_MAX_ITERATIONS`，并携带当前协议消息链。

`ConversationServiceImpl` 保存助手消息时会把运行状态写入 `runStatus`。只有暂停于最大迭代数且存在消息链时，才把 `AgentRunCheckpoint` 序列化到 `checkpointJson`。下一轮 `AgentTurnContextService.prepare` 会优先从检查点恢复历史；如果是旧数据或检查点为空，则退化为从展示事件生成文本续接上下文。

## 6. 提示词组装

`ReactPromptAssembler` 不再维护一整块固定 system prompt，而是按当前真实工具按需加载 section：

| Section | 加载条件 |
| --- | --- |
| identity/workspace 基础约束 | 始终加载 |
| browser 工具策略 | 当前工具含浏览器工具时 |
| Skill 系统约束 | 当前工具含技能相关工具时 |
| `run_subagent` 子代理约束 | 当前工具含 `run_subagent` 时 |
| `todo_write` 任务清单约束 | 当前工具含 `todo_write` 时 |
| MCP 动态工具管理约束 | 当前工具含 MCP 工具时 |
| 运行时上下文 | 本轮时间快照非空时 |
| 当前工具目录 | 始终加载，按实际可用工具生成 |

这样减少无关指令噪声，新增工具类型时只添加对应 section。SOCIAL 轮次用 `assembleSocial(runtimeContext)` 替代，只保留极简社交提示和本轮时间快照，`setUseRawSystemPrompt(true)` 跳过常规 section 加载。

## 7. TodoState 执行闭环

### 7.1 TodoState 规则

`todo_write` 工具把模型显式提交的完整 todo 快照写入 `AgentTodoService`。规则：

- 同一会话同一时间最多一个 `in_progress`。
- `blocked` 状态必须包含 `blocker`（阻塞原因）。
- `cancelled` 状态必须包含 `reason`。
- `completed` 状态建议包含 `evidence`（完成证据），缺少会产生提醒。
- 未完成的 todo 不能被下一次更新静默删除，必须继续推进、标记 blocked 或 cancelled。

### 7.2 FinalTodoGuardHook

`FinalTodoGuardHook` 在模型准备最终回答时拦截，检查 TodoState：

1. 是否仍有 `pending` 或 `in_progress` 的 todo。
2. `completed` 的 todo 是否缺少 `evidence`。

任一条件命中时，注入一条用户消息强制 ReactAgent 继续执行或修正状态，不让多步任务未闭环就结束。该 Hook 只在 `policy.shouldEnableStopHook()` 为 true 时注册，SOCIAL 轮次不注册。

## 8. 子代理

### 8.1 run_subagent

`run_subagent` 从父 ReactAgent fork 子 Agent，继承 PreToolUse/PostToolUse Hook。子代理类型：

| 类型 | max-iterations | 工具集 |
| --- | --- | --- |
| `analyzer` | 10 | 分析类 |
| `searcher` | 5 | 搜索类 |
| `browser` | 10 | 浏览器类 |
| `general` | 15 | 通用 |

每种类型可单独开关和设最大迭代数，由 `agent.sub-agent.types.*` 配置。`run_in_background=true` 时任务交给 `BackgroundTaskManager` 后台运行。

### 8.2 父 Agent 注入

`ReactAgentHookService.wireSubAgentParent` 在 Hook 装配阶段遍历工具列表，找到 `RunSubagentTool` 后把当前 `ReactAgent` 作为父 Agent 注入。子代理通过父 Agent 引用 fork 出受限工具集。

## 9. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| 规划为空或格式不对 | `PlanAgent.validatePlan` 日志、repairPrompt | 两次校验都失败会走 fallback，检查模型输出是否含协议标记 |
| 规划包含 tool_calls | 模型输出了执行协议标记 | `EXECUTION_PROTOCOL_MARKERS` 会拦截，走纠正或 fallback |
| 社交消息也走了规划 | `judgeIntent` 输出、`TurnPolicy.forMode` | 判断失败降级 TASK，检查意图分类是否异常 |
| 最终回答被反复拦截 | `FinalTodoGuardHook`、TodoState、evidence | 检查是否有未完成 todo 或缺少证据的 completed |
| 工具调用解析失败 | LLM 返回格式、tool_calls 字段、ReAct 文本 | 优先原生格式，回退正则解析，检查模型是否支持 function calling |
| 子代理超时 | `agent.sub-agent.timeout-seconds`、max-iterations | 超时 120 秒，迭代数按类型限制 |
| 并发工具执行异常 | `agent.hook.concurrent-tool-execution-enabled` | 置 false 退化为串行排查 |
| SOCIAL 轮次仍有工具 | `ReactAgentFactory.createForChat`、TurnPolicy | SOCIAL 创建无工具执行器，检查 policy 是否正确传入 |
| 达到上限后没有续接 | `runStatus`、`checkpointJson`、`AgentContinuation` | 新数据应优先从协议级检查点恢复 |
| 时间相关回答互相矛盾 | `AgentTimeContext`、`current_time`、`agent.time-zone` | 同轮快照应一致；跨时区或新鲜时刻才调用工具 |

## 10. 术语速查

本节用于 Obsidian 悬浮预览。阅读正文时，把鼠标停在带链接的术语上，可以快速看到这里的释义。

### PlanAgent

`PlanAgent` 是任务前建模层，不带工具，只调用一次 LLM，产出结构化任务模型（目标状态、当前判断、成功信号、初始策略）。它负责建立任务方向，不预演运行时过程。规划输出有结构化校验和纠正机制，两次失败后生成最小 fallback 计划。

### ReactAgent

`ReactAgent` 是执行层，负责 function calling 和 ReAct 工具调用循环。它支持同步和流式两种模式，通过 Hook 机制扩展行为边界。工具调用解析优先用原生 tool_calls，回退到 ReAct 文本正则。

### judgeIntent

`judgeIntent` 是 `PlanAgent` 的静态方法，一次不带工具的轻量 LLM 调用，只输出 SOCIAL 或 TASK。用于决定本轮是否走社交轻量路径。判断失败降级为 TASK，确保不阻断对话。

### REQUIRED_SECTIONS

`REQUIRED_SECTIONS` 是 PlanAgent 规划输出的四个必须段落：`### 目标状态`、`### 当前判断`、`### 成功信号`、`### 初始策略`。校验时按顺序检查位置和内容非空。

### EXECUTION_PROTOCOL_MARKERS

`EXECUTION_PROTOCOL_MARKERS` 是不应出现在规划输出中的工具调用协议标记列表，包括 `tool_calls`、`function_call`、`<tool_call`、`<invoke`、`<｜｜dsml｜｜`。命中时规划被判为无效，走纠正或 fallback。

### TodoState

`TodoState` 是 Agent 执行过程中的任务看板，记录 todo 的状态、验证和阻塞原因。通过 `todo_write` 工具显式维护。`FinalTodoGuardHook` 在最终回答前检查是否闭环。

### FinalTodoGuardHook

`FinalTodoGuardHook` 是 StopHook 实现，在模型准备最终回答时检查 TodoState 是否还有未完成的 todo 或缺少 evidence 的 completed。命中时注入消息强制继续。只在 `policy.shouldEnableStopHook()` 为 true 时注册。

### ReactPromptAssembler

`ReactPromptAssembler` 是执行器系统提示组装器，按当前真实工具按需加载 section（browser 策略、Skill 约束、子代理约束、todo 约束、MCP 约束等），并在动态边界后追加本轮时间快照。SOCIAL 轮次用 `assembleSocial(runtimeContext)` 替代。

### BackgroundTaskManager

`BackgroundTaskManager` 管理后台子代理任务，最大并发由 `agent.background.max-concurrent` 控制。主循环后续轮次从它收集完成结果并注入对话。

### TurnPolicy

`TurnPolicy` 是单轮对话策略开关，控制本轮是否注入规划、工具、技能、工作区和 StopHook。SOCIAL 对应 LITE（这些开关全 false），TASK 和 AMBIGUOUS 对应 FULL（全 true）。知识库自动检索由请求级 `knowledgeEnabled` 控制。

### AgentRunCheckpoint

`AgentRunCheckpoint` 是最大迭代暂停时保存的协议级检查点。它保留模型消息顺序、toolCallId 和 toolCalls，使下一轮能从最后一次工具观察后继续，而不是依赖前端展示事件反推协议。

### AgentTimeContext

`AgentTimeContext` 是本轮不可变时间快照，包含当前日期、带时区时间、星期和 UTC 时间。规划器、执行器、社交轮和子代理共享同一个快照。

### CurrentTimeTool

`CurrentTimeTool` 是只读工具，工具名 `current_time`，用于获取调用时刻的新时间或指定 IANA 时区的时间。普通相对时间优先使用 `AgentTimeContext`，最新事实仍需通过搜索或业务工具验证。

## 11. 相关页面

[[Agent 编排模块]] · [[Agent 工具调用]] · [[工具系统模块]] · [[LLM 接入与错误处理]]
