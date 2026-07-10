# ReactAgent 解耦 + 社交轮独立路径 实施方案

## 一、背景与问题

用户原始痛点：发个「你好」，模型就跑去介绍工作目录、自报「我有这些工具你要我干嘛」。

定位到的根因（已在对话中确认）：

1. **`ReactPromptAssembler` 的 `IDENTITY_SECTION` + `WORKSPACE_SECTION` 无条件加载**，与是否装配工具无关。这是「你好 → 工作目录」的直接源头，而当前 `TurnPolicy` 只跳 DB 里的 `workspaceMemoryContext`，根本没碰这两段。
2. **`ReactAgent.runStream` 把对话流式骨架与 ReAct 工具循环焊死**（1500 行单类，SSE 事件产出 + 消息保存 + token 统计 + 历史压缩 + 后台任务消化 + 工具调度 + hook 全糊一起）。要分叉一条社交路径就只能复制，两套要同步维护。
3. **`TurnPolicy` 是半成品**：`LightweightChatRouter` 标 @Deprecated 仍被 `prepare()` 调用，与 `policy.shouldPlan()` 双信号源并存；`AgentTurnContext` 有两个 @Deprecated 字段；`ReactAgentHookService` 8 个 telescoping 重载生产只调最完整那个；`TurnModeClassifier` 关键词法脆弱无单测。
4. **`skillPrompt` 不受 `TurnPolicy` 控制**：SOCIAL 轮只要启用了技能，模型照样可能自报「我有这些技能」。

## 二、目标

- 解耦：把对话流式骨架从 `ReactAgent` 抽出，任务路径与社交路径共享，SSE 协议/消息格式只在一处。
- 社交独立路径：SOCIAL 轮不走 ReactAgent 工具循环，走轻量对话调用 + 对话人格。
- 意图判断：用 LLM 判意图（PlanAgent 加 `judgeIntent`），判不准落 TASK，带历史。
- assembler 社交分支：跳 IDENTITY/WORKSPACE/工具段，只出对话人格。
- 清理半成品：删 LightweightChatRouter、双信号源、8 重载、@Deprecated 字段。

## 三、核心设计

### 3.1 StreamConversationHarness（新增，流式骨架）

有状态实例（每个 turn new 一个），托管流式原语，不含任何工具循环逻辑：

```java
final class StreamConversationHarness {
    StreamConversationHarness(LlmService llmService, ConversationService conversationService,
                              TokenUsageService tokenUsageService, String sessionId, Long userId);

    void thinkingStart(FluxSink<SseEvent> sink, int iteration);
    void thinkingEnd(FluxSink<SseEvent> sink);
    void emitToken(FluxSink<SseEvent> sink, String token);
    void emitReasoning(FluxSink<SseEvent> sink, String reasoning);
    void accumulateUsage(LlmUsage usage);
    void compressIfNeeded(List<ChatMessage> messages);
    void saveAssistant(String content, String reasoning, List<Map<String,Object>> events);
    void saveInterrupted(String partialContent, String partialReasoning);
    LlmUsage totalUsage();
    void emitAnswer(FluxSink<SseEvent> sink, String content, String reasoning);
    void emitDone(FluxSink<SseEvent> sink, int iteration);
    void emitError(FluxSink<SseEvent> sink, String msg);
    void emitInterrupted(FluxSink<SseEvent> sink, String reason);
}
```

- `ReactAgent.runStream` 改为：持有 harness，自己只保留「调 `chatWithToolsStream` → 收集 tool_call → 工具调度 + hook → 收尾/中断」，流式/保存/统计/压缩全部委托 harness。
- 社交流路径：同样持 harness，轮次逻辑 = 一次 `chatStream(对话人格, history)`，无工具无 hook 无循环。

> 同步路径 `chat()` 走 `run()` 拿 `AgentResponse`，非流式。社交同步路径单独简化：`judgeIntent` SOCIAL → 直接 `chatWithSystemResponse(对话人格, history)` → `conversationService.addAssistantMessage` → 返回。不强行走 harness，避免把流式概念塞进同步路径。

### 3.2 PlanAgent.judgeIntent（方案 A，独立轻量入口）

- 不依赖 tools/skills/workspace 的工厂/静态方法，只喂 `userMessage` + 历史。
- 独立 prompt：「纯社交还是需要执行？只回 SOCIAL/TASK，不确定回 TASK」。
- 复用 `filterAndTrimHistory`（6 条够判意图）。
- 输出受控单 token；解析失败/异常 → TASK。
- 由 `AgentPlannerService` 暴露 `judgeIntent(context)`，返回 `TurnMode`，`prepare()` 据此产出 `TurnPolicy`（替换 `TurnModeClassifier` 关键词法）。

### 3.3 ReactPromptAssembler 社交分支

- 新增对话人格常量（轻、自然、不自报工具/工作目录、用户要做事再深入）。
- 新增 `assembleSocial()`：只返回对话人格段，跳 IDENTITY/WORKSPACE/所有工具段。
- `AgentTurnContextService.prepare()`：SOCIAL 轮 `systemPrompt` 走 `assembleSocial()`。

### 3.4 skillPrompt 纳入 policy

- `TurnPolicy` 加 `shouldInjectSkill`（SOCIAL=false）。`prepare()` 里 `skillPrompt` 按 policy 注入。

## 四、分阶段实施（每阶段独立可提交、可验证）

> 顺序设计为：阶段 1 先让痛点消失（仍走 ReactAgent 空 tools 过渡），阶段 2-3 完成解耦与独立路径，阶段 4 清理，阶段 5 文档。每阶段产出独立提交。

### 阶段 1 — 治本痛点（先见效，过渡期仍走 ReactAgent 空 tools）
- `ReactPromptAssembler.assembleSocial()` + 对话人格常量。
- `TurnPolicy` 加 `shouldInjectSkill`，`prepare()` 按 policy 注入 skillPrompt；SOCIAL 轮 `systemPrompt` 走 `assembleSocial()`。
- `PlanAgent.judgeIntent` + `AgentPlannerService.judgeIntent`，`prepare()` 用它产出 policy（替换关键词分类）。
- 装配分叉：SOCIAL 轮 `ReactAgentFactory` 给空 tools + 不挂 Stop hook（仍走 ReactAgent 当壳）。
- 验收：发「你好」不再提工作目录/工具/技能；TASK 轮全能力不变。
- 风险：中。改 prepare + assembler + PlanAgent。加单测：judgeIntent、assembleSocial。

### 阶段 2 — 抽 StreamConversationHarness（解耦，行为不变）
- 新建 `StreamConversationHarness`。
- 把 `ReactAgent.runStream` 的流式/保存/统计/压缩逻辑物理迁移到 harness，`runStream` 调用 harness 原语。
- 严格保持外部 `Flux<SseEvent>` 协议与消息保存格式不变。
- 验收：现有 `ReactAgentHookServiceTodoGuardTest` / `VisionTest` 全绿；手测流式任务轮行为不变。
- 风险：高（动核心流式路径）。缓解：纯搬运、不重构逻辑、对比事件序列。

### 阶段 3 — 社交独立路径（去掉杀鸡牛刀）
- SOCIAL 轮不再走 `ReactAgentFactory.createForStream`，改走 harness + 一次 `chatStream(对话人格, history)`。
- 复用 harness 的 SSE/保存/统计/中断；`AgentServiceImpl` 流式入口按 policy 分叉。
- 验收：社交轮不进工具循环、无 hook 注册；流式输出/保存/标题生成与任务轮一致。
- 风险：低（新增路径，不动任务轮）。

### 阶段 4 — 清理半成品
- 删 `LightweightChatRouter`；`prepare()` 不再调它，规划决策只认 `policy.shouldPlan()` 一处。
- 删 `AgentTurnContext` 的 `shouldRunPlanAgent` / `skipPlanningByLightweightRoute` 两 @Deprecated 字段。
- `ReactAgentHookService` 8 重载收敛为 1 个（或 1 个 + `record HookConfig`）。
- 删 `TurnModeClassifier` 关键词法（被 `judgeIntent` 取代）；保留 `TurnMode`/`TurnPolicy`/`TurnPolicyResolver`（judgeIntent 产出 mode）。
- 验收：无死代码、无 @Deprecated 残留；编译 + 全量测试绿。
- 风险：低（删已知无调用方代码）。

### 阶段 5 — 文档与 ADR
- 新增 ADR-016「ReactAgent 流式骨架解耦 + 社交轮独立路径」，标注取代 ADR-005。
- 更新 `docs/project-spec.md` 相关章节（执行器/编排层描述）。

## 五、风险与回退

- 阶段 2 是唯一高风险点：必须保证「纯搬运、行为不变」，用现有测试 + 手测事件序列兜底。若出回归，可回退该提交，阶段 1 的过渡态仍可用。
- 阶段 1 过渡态（ReactAgent 空 tools）本身可独立运行，即使后续阶段暂停也不阻塞。
- `judgeIntent` 调用失败必须降级 TASK，不能让意图判断挂掉对话。

## 六、不在本次范围

- 细粒度按需装配（浏览器任务只给浏览器工具等）——递归依赖意图判断，后续单独做。
- 同步社交路径走 harness 的统一——流式优先，同步保持简化直调。
