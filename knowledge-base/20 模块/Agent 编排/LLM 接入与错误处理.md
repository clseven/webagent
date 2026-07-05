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
  - src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java
updated: 2026-07-06
---

# LLM 接入与错误处理

## LLM 抽象

`LlmService` 是统一模型接口，支持：

- 普通聊天。
- 系统提示 + 消息列表。
- function calling 工具调用。
- 工具调用流式输出。
- OpenAI vision 格式的多模态 content parts。

`BaseLlmServiceImpl` 负责通用 HTTP 请求、响应解析、工具调用解析、流式 chunk 解析和 token 用量抽取。具体模型服务通过 Spring Bean 名称区分用途。

## 当前 Bean 分工

- `executorLlm`：默认 `DeepSeekLlmServiceImpl`，读取 `agent.llm.executor`，负责 PlanAgent 和 ReactAgent 主流程。
- `plannerLlm`：`ZhipuLlmServiceImpl`，读取 `agent.llm.planner`，当前主要用于会话标题等轻量调用。
- `visionLlm`：`VisionLlmServiceImpl`，读取 `agent.llm.vision`，默认 Agnes `agnes-2.0-flash`，只用于图片观察。

## 视觉观察链路

1. 执行器调用 `view_image` 工具读取图片。
2. `ImageBuffer` 保存图片字节和元数据。
3. `ReactAgentHookService.viewImageHook` 在工具执行后触发。
4. Hook 构造 vision 消息，调用 `visionLlm.chatWithSystemResponse`。
5. 视觉模型返回客观观察文本，Hook 把它作为额外消息注入 ReactAgent。
6. 主执行模型继续基于文本观察做后续推理和最终回复。

这样避免让 DeepSeek executor 直接处理图片字节，也避免每轮 LLM 调用都扫描历史消息寻找图片。

## DeepSeek 错误策略

DeepSeek executor 有专门错误策略，用于区分：

- 可重试错误：网络、临时服务异常、部分 5xx。
- 不应重试错误：认证失败、参数错误、上下文不可修复错误。
- 流式异常结束：保留 partial thinking/answer，并在前端标记中断或错误。

错误策略的目标是减少“模型调用失败导致整轮状态丢失”，同时避免对不可恢复错误盲目重试。

## Token 统计

`AgentServiceImpl` 和 `AgentPlannerService` 会按 userId/sessionId 记录 token，usageType 区分 title、planner、executor 等用途。前端 `TokenStats` 页面通过 [[后端 HTTP API]] 查看汇总、每日和模型维度统计。

## 相关页面

[[Agent 编排模块]] · [[计划与 ReAct 执行]] · [[工具系统模块]] · [[运行配置]]