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
updated: 2026-07-06
---

# Agent 工具调用

## 基本流程

1. [[Agent 编排模块]] 为当前会话准备真实可用工具列表。
2. `ReactPromptAssembler` 根据工具列表拼装系统提示。
3. `ToolDefinition` 以 function calling JSON Schema 传给 LLM。
4. LLM 返回 `tool_call`。
5. `ReactAgent` 先触发 `PreToolUseHook`。
6. 如果未被拦截，按工具名找到 `Tool` 并执行。
7. 工具返回字符串 observation。
8. `ReactAgent` 触发 `PostToolUseHook`，必要时注入额外消息。
9. observation 作为 `tool` 消息写回，模型继续下一轮或最终回答。
10. 最终回答前触发 `StopHook`。

## 流式展示

流式路径会向前端发送：

- `plan`：规划文本。
- `thinking` / reasoning token。
- `tool_call`：工具名、参数、步骤编号和行动说明。
- `observation`：工具结果、耗时、行动说明。
- `answer`：最终答案 token。
- `status` / `interrupted` / `error`。

`AgentActionNarrator` 把工具调用转成更友好的状态文案，例如“正在查看图片”“正在更新任务进度”。

## Hook 行为

- `viewImageHook` 在 `view_image` 后调用视觉模型，并把观察文本注入。
- `largeOutputHook` 可处理过大的工具输出。
- `FinalTodoGuardHook`（Stop Hook）两层把关最终回答：先看 TodoState 前置硬信号（未闭环/缺证据直接拦），干净后注入证据自检提示让模型基于证据自判该不该收尾。由 `ReactAgent` 持有的收尾尝试计数收敛（连续想收尾却不补查则强制放行），另一端由 `MAX_ITERATIONS` 兜底。搜索不再有专属 Hook，所有工具一视同仁接受证据验收。

## 失败行为

- 参数错误通常由工具返回中文错误字符串，模型可继续修正参数。
- 工具异常会被转换成 observation，避免直接打断整个 Agent 循环。
- Hook 拦截也会形成 observation。
- 达到迭代上限时，执行器返回未完成提示。

## 相关页面

[[工具系统模块]] · [[工具目录]] · [[计划与 ReAct 执行]]