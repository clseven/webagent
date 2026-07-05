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
  - src/main/java/com/example/sandbox/web/service/impl/AgentTodoService.java
  - src/main/java/com/example/sandbox/web/service/impl/FinalTodoGuardHook.java
updated: 2026-07-06
---

# 计划与 ReAct 执行

## PlanAgent

PlanAgent 是任务前建模层。它根据用户目标、会话历史、工作区提示、启用 Skill、应用知识库和可用工具，生成执行计划。计划不会直接调工具，而是提供：

- 目标状态。
- 关键步骤和成功信号。
- 工具选择建议。
- 对 MCP、浏览器或文件操作的约束提醒。

当前 `AgentPlannerService` 沿用 `executorLlm` 执行规划，因此默认也是 DeepSeek。`plannerLlm` 主要保留给标题生成等轻量调用。

## ReactAgent

ReactAgent 是执行层。它把当前真实可用工具定义交给 LLM，按 function calling 协议执行工具，并把 observation 写回消息列表。同步和流式路径都支持：

- `PreToolUseHook`：工具执行前拦截，返回非空字符串时阻止执行并把字符串作为 observation。
- `PostToolUseHook`：工具执行后注入额外消息，例如 `view_image` 的视觉观察。
- `StopHook`：模型准备最终回答时拦截并强制继续，例如 TodoState 未闭环。
- 后台任务通知：从 `BackgroundTaskManager` 收集子代理结果并注入对话。

## 提示词组装

`ReactPromptAssembler` 不再维护一整块固定 system prompt，而是按当前真实工具加载 section：

- identity/workspace 基础约束。
- browser 工具策略。
- Skill 系统约束。
- `run_subagent` 子代理约束。
- `todo_write` 运行时任务清单约束。
- MCP 动态工具管理约束。
- 当前工具目录。

这样能减少无关指令噪声，也让新增工具类型时只添加对应 section。

## TodoState 执行闭环

`todo_write` 工具把模型显式提交的完整 todo 快照写入 `AgentTodoService`。规则包括：

- 同一会话同一时间最多一个 `in_progress`。
- `blocked` 必须包含 `blocker`。
- `cancelled` 必须包含 `reason`。
- `completed` 建议包含 evidence；缺少 evidence 会产生提醒。
- 未完成 todo 不能被下一次更新静默删除，必须继续推进、标记 blocked 或 cancelled。

`FinalTodoGuardHook` 在最终回答前检查 TodoState。若仍有 pending/in_progress，或 completed 缺少 evidence，会注入一条用户消息强制 ReactAgent 继续执行或修正状态。

## 子代理和后台任务

`run_subagent` 从父 ReactAgent fork 子 Agent，并继承 PreToolUse/PostToolUse Hook。子代理类型包括 analyzer、searcher、browser、general，各类型可限制工具集合。`run_in_background=true` 时，任务交给 `BackgroundTaskManager`，主循环后续收集结果并消化。

## 面试可讲点

这套设计的重点不是“让模型想一步做一步”，而是把任务前规划、执行中状态、工具观察、图片观察和最终门禁拆成不同层：PlanAgent 管目标，ReactAgent 管动作，TodoState 管证据，Hook 管边界事件。

## 相关页面

[[Agent 编排模块]] · [[Agent 工具调用]] · [[工具系统模块]] · [[LLM 接入与错误处理]]