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
updated: 2026-07-06
---

# Agent 编排模块

## 职责

Agent 编排模块负责把一次用户消息转成“准备上下文 → 规划 → ReAct 执行 → 保存结果 → 记录用量”的完整闭环。当前代码已经把早期集中在 `AgentServiceImpl` 的逻辑拆成多个小服务：

- `AgentServiceImpl`：同步/流式入口、会话归属校验、保存消息、记录 token、清理工具运行态。
- `AgentTurnContextService`：准备历史、上传文件上下文、应用配置、Skill prompt、知识库增强和工具上下文。
- `AgentPlannerService`：调用 PlanAgent 生成任务计划，并记录规划 token。
- `ReactAgentFactory`：按同步或流式路径创建 `ReactAgent`。
- `ReactAgentHookService`：集中注册 Hook，注入 `view_image` 视觉观察、搜索策略门禁、TodoState 最终门禁和 `run_subagent` 父 Agent。

## 同步与流式入口

- `chat(sessionId, userMessage)`：返回完整 `ChatMessage`，适合非流式调用。
- `chatStream(sessionId, userMessage)`：返回 `Flux<SseEvent>`，前端通过 SSE 展示计划、思考、工具调用、观察和最终答案。

两条路径共享大部分上下文准备和执行器创建逻辑。流式路径在规划后先发送 `plan` 事件，再把 `ReactAgent.runStream` 的事件转发给前端。

## 执行阶段

1. 校验会话属于当前用户。
2. 准备 `AgentTurnContext`，包括历史消息、上传文件、应用配置、Skill prompt、工具列表和知识库上下文。
3. 调用 `AgentPlannerService.plan` 生成计划。同步路径使用 `plan` 用量标签，流式路径使用 `plan_stream`。
4. `ReactAgentFactory` 创建执行器，并由 `ReactAgentHookService` 注册 Hook。
5. `ReactAgent` 按 function calling / ReAct 循环调用工具。
6. 工具结果、Hook 注入消息、TodoState 门禁和后台子代理通知会继续影响后续模型调用。
7. 保存助手消息和事件列表，记录 executor token，用 `AgentToolContextService.clearRuntimeState` 清理运行态。

## 当前设计特点

- 规划和执行分离：PlanAgent 提供目标、成功信号和初始策略，ReactAgent 负责行动。
- 多模型分工：DeepSeek 默认负责 planner/executor，Agnes 只负责图片观察。
- Hook 化扩展：图片观察、搜索策略、TodoState 最终门禁和大输出处理都通过 Hook 进入执行循环。
- TodoState 闭环：多步任务可用 `todo_write` 显式维护目标、状态、证据和阻塞原因。
- 子代理：`run_subagent` 通过父 Agent fork 出受限工具集执行子任务，可同步或后台运行。
- MCP 动态工具：MCP server 的工具会由 `McpClientToolProvider` 转成普通 `Tool`，再进入同一执行器。

## 重要约束

- 编排层不直接实现工具逻辑，不直接读写 MCP 配置文件，不直接处理图片字节。
- 工具上下文必须在同步/流式结束和异常路径中清理。
- 新增 Hook 时要同时覆盖同步和流式路径。
- TodoState 只记录执行状态，不负责调度工具或并行批次。
- 当前普通工具调用仍按单个 tool call 处理；并行能力主要交给 `run_subagent`。

## 相关页面

[[计划与 ReAct 执行]] · [[LLM 接入与错误处理]] · [[Agent 工具调用]] · [[工具系统模块]] · [[创建会话并完成一次对话]]