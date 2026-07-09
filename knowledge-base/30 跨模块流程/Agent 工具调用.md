---
project: webagent-clean
type: flow
status: verified
area:
  - agent
  - tools
  - sandbox
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentActionNarrator.java
  - src/main/java/com/example/sandbox/web/service/Tool.java
  - src/main/java/com/example/sandbox/web/service/tool
updated: 2026-07-09
---

# Agent 工具调用

## 1. 基本流程

1. [[Agent 编排模块]] 的 `AgentToolContextService.build` 为当前会话准备真实可用工具列表。
2. `ReactPromptAssembler` 根据工具列表按需加载 section 拼装系统提示。
3. `ToolDefinition` 以 function calling JSON Schema 传给 LLM。
4. LLM 返回 `tool_calls`（或 ReAct 文本）。
5. `ReactAgent` 先触发 `PreToolUseHook`，返回非空字符串时阻止执行。
6. 如果未被拦截，按工具名找到 `Tool` 并执行（可并发或串行）。
7. 工具返回字符串 observation。
8. `ReactAgent` 触发 `PostToolUseHook`，必要时注入额外消息。
9. observation 作为 `tool` 消息写回，模型继续下一轮或最终回答。
10. 最终回答前触发 `StopHook`。

## 2. 工具调用解析

解析分两层：

1. **原生 tool_calls**：解析 LLM 返回的 `tool_calls` 字段，收集全部 tool_call。一个响应可含多个调用，可并发调度。工具名用 `TOOL_NAME_PATTERN` 校验。
2. **ReAct 文本回退**：用正则匹配 `Action: tool_name` 和 `Action Input:`，再解析 `key=value` 参数。兼容老模型。

## 3. 并发执行

`concurrentToolExecutionEnabled` 控制是否并发。开启时按 [[#ToolSideEffect]] 调度：

| 副作用类型 | 调度规则 |
| --- | --- |
| READ | 可并发，与其他 READ 同时执行 |
| WRITE | 串行，与 READ 互斥 |
| EXCLUSIVE | 完全串行 |

出问题可置 `agent.hook.concurrent-tool-execution-enabled=false` 退化为串行。

## 4. 流式展示

流式路径向前端发送的事件：

| 事件类型 | 内容 |
| --- | --- |
| `plan` | 规划文本 |
| `thinking` | reasoning token |
| `tool_call` | 工具名、参数、步骤编号和行动说明 |
| `observation` | 工具结果、耗时、行动说明 |
| `answer` | 最终答案 token |
| `status` / `interrupted` / `error` | 状态和异常 |

`AgentActionNarrator` 把工具调用转成更友好的状态文案，例如"正在查看图片""正在更新任务进度"。

## 5. Hook 行为

### 5.1 viewImageHook

`view_image` 工具执行后触发：

1. 从 `ImageBuffer.take(sessionId)` 取出图片数据。
2. 构造 vision 消息，调用 `visionLlm.chatWithSystemResponse`。
3. 视觉模型返回客观观察文本，作为 user 消息注入。
4. 失败降级为"图片已加载但无法分析"提示，不阻断主流程。

### 5.2 largeOutputHook

处理过大的工具输出（仅流式路径注册）。

### 5.3 FinalTodoGuardHook

Stop Hook，两层把关最终回答：

1. 先看 TodoState 前置硬信号：未闭环或缺证据直接拦截。
2. 干净后注入证据自检提示，让模型基于证据自判该不该收尾。
3. 由 `ReactAgent` 持有的收尾尝试计数收敛：连续想收尾却不补查则强制放行。
4. 另一端由 `MAX_ITERATIONS` 兜底。

搜索不再有专属 Hook，所有工具一视同仁接受证据验收。

### 5.4 FileStateCheckHook

Pre + Post Hook，防止 TOCTOU。受 `agent.hook.state-check-enabled` 控制，关闭后退化为无校验。

## 6. 失败行为

| 场景 | 行为 |
| --- | --- |
| 参数错误 | 工具返回中文错误字符串，模型可继续修正参数 |
| 工具异常 | 转换成 observation，避免打断 Agent 循环 |
| Hook 拦截 | 形成 observation |
| 迭代上限 | 执行器返回未完成提示 |
| 视觉模型失败 | 降级为提示消息，不报错 |

## 7. 术语速查

### ToolSideEffect

工具副作用类型枚举，分 READ（可并发）、WRITE（串行，与 READ 互斥）、EXCLUSIVE（完全串行）三种。默认 EXCLUSIVE 最保守。并发调度器据此决定能否并行执行。

### AgentActionNarrator

`AgentActionNarrator` 把工具调用转成更友好的状态文案，用于前端流式展示。例如把 `view_image` 转成"正在查看图片"。

## 8. 相关页面

[[工具系统模块]] · [[工具目录]] · [[计划与 ReAct 执行]] · [[Agent 编排模块]]