# Agent 超限检查点续跑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 达到 200 次 ReAct 迭代时保留完整模型消息检查点，下一轮从已完成工具结果后继续；旧超限会话使用现有 `events_json` 生成续接上下文；消息达到约 200K token 才压缩。

**Architecture:** 继续使用 `chat_message` 作为会话持久化边界，在 assistant 消息上新增 `run_status` 与 `checkpoint_json`。`events_json` 只承担前端展示和旧数据兼容，`checkpoint_json` 保存 role/content/reasoning/tool_calls/tool_call_id 的协议级消息链；`AgentTurnContextService` 在新一轮入库用户消息前恢复最近的暂停检查点。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Data JPA、Jackson、JUnit 5、Mockito、AssertJ、MySQL LONGTEXT。

## Global Constraints

- 所有新增或修改的注释、Javadoc 使用中文。
- 保留工作区现有未提交改动，不回滚、不覆盖无关内容。
- 不提交、不推送；由用户决定后续 Git 操作。
- `events_json` 保持现有前端结构，不用展示事件伪造原生 tool calling 消息。
- `checkpoint_json` 不持久化图片 base64，只保存可安全重放的文本、reasoning 和工具协议字段。
- 工具调用组必须保持 assistant `tool_calls[]` 与随后 tool result 的 ID 对应关系。

---

### Task 1: 定义检查点模型与编解码

**Files:**
- Create: `src/main/java/com/example/sandbox/web/model/llm/AgentRunStatus.java`
- Create: `src/main/java/com/example/sandbox/web/model/llm/ChatMessageCheckpoint.java`
- Create: `src/main/java/com/example/sandbox/web/model/llm/AgentRunCheckpoint.java`
- Create: `src/main/java/com/example/sandbox/web/model/llm/AgentContinuation.java`
- Modify: `src/main/java/com/example/sandbox/web/model/entity/ChatMessage.java`
- Modify: `src/main/java/com/example/sandbox/web/model/converter/EntityConverter.java`
- Test: `src/test/java/com/example/sandbox/web/model/converter/AgentRunCheckpointCodecTest.java`

**Interfaces:**
- Produces: `AgentRunCheckpoint.fromMessages(List<ChatMessage>)`
- Produces: `AgentRunCheckpoint.toMessages()`
- Produces: `EntityConverter.serializeCheckpoint(AgentRunCheckpoint)`
- Produces: `EntityConverter.parseCheckpoint(String)`

- [ ] **Step 1: 写失败测试，要求并发 tool_calls 和 tool_call_id 无损往返**

```java
@Test
void 检查点应无损恢复并发工具调用和对应结果() {
    List<ChatMessage> messages = List.of(
            ChatMessage.userMessage("继续任务"),
            ChatMessage.assistantToolCallsMessage(List.of(
                    new LlmToolCall("call_1", "read_file", Map.of("path", "/a")),
                    new LlmToolCall("call_2", "web_search", Map.of("query", "x")))),
            ChatMessage.toolMessage("call_1", "A"),
            ChatMessage.toolMessage("call_2", "B"));

    String json = EntityConverter.serializeCheckpoint(AgentRunCheckpoint.fromMessages(messages));
    List<ChatMessage> restored = EntityConverter.parseCheckpoint(json).toMessages();

    assertThat(restored.get(1).getToolCalls()).extracting(LlmToolCall::id)
            .containsExactly("call_1", "call_2");
    assertThat(restored.get(2).getToolCallId()).isEqualTo("call_1");
    assertThat(restored.get(3).getToolCallId()).isEqualTo("call_2");
}
```

- [ ] **Step 2: 运行测试并确认因检查点类型不存在而失败**

Run: `mvn "-Dtest=AgentRunCheckpointCodecTest" test`

Expected: 编译失败，提示 `AgentRunCheckpoint` 或编解码方法不存在。

- [ ] **Step 3: 实现最小检查点模型和 ChatMessage 协议恢复工厂**

```java
public enum AgentRunStatus {
    COMPLETED,
    PAUSED_MAX_ITERATIONS
}

public record ChatMessageCheckpoint(String role, String content, String reasoning,
                                    Long timestamp, String toolCallId,
                                    List<LlmToolCall> toolCalls) {
    public ChatMessage toMessage() {
        return ChatMessage.restoreProtocol(
                role, content, reasoning, timestamp, toolCallId, toolCalls);
    }
}
```

- [ ] **Step 4: 用 Jackson 序列化/反序列化 `AgentRunCheckpoint`，损坏 JSON 返回空检查点并记录兼容行为**

- [ ] **Step 5: 运行编解码测试并确认通过**

Run: `mvn "-Dtest=AgentRunCheckpointCodecTest" test`

Expected: `Tests run: 1, Failures: 0, Errors: 0`。

---

### Task 2: 在现有 chat_message 中保存暂停状态和检查点

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/model/entity/ChatMessageEntity.java`
- Modify: `src/main/java/com/example/sandbox/web/repository/ChatMessageRepository.java`
- Modify: `src/main/java/com/example/sandbox/web/service/ConversationService.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/ConversationServiceImpl.java`
- Create: `src/main/java/com/example/sandbox/web/service/impl/AgentContinuationContextFormatter.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/ConversationContinuationTest.java`

**Interfaces:**
- Consumes: `AgentRunCheckpoint.fromMessages(...)`
- Produces: `ConversationService.addAssistantMessage(..., AgentRunStatus, List<ChatMessage>)`
- Produces: `ConversationService.getLatestContinuation(String)`

- [ ] **Step 1: 写失败测试，验证暂停消息保存 `run_status` 和 `checkpoint_json`**

```java
conversationService.addAssistantMessage(
        sessionId, limitMessage, null, events,
        AgentRunStatus.PAUSED_MAX_ITERATIONS, checkpointMessages);

ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
verify(messageRepository).save(captor.capture());
assertThat(captor.getValue().getRunStatus()).isEqualTo("PAUSED_MAX_ITERATIONS");
assertThat(captor.getValue().getCheckpointJson()).contains("call_1");
```

- [ ] **Step 2: 写失败测试，验证旧超限消息从 `events_json` 生成纯文本续接上下文**

```java
assertThat(continuation.exactCheckpoint()).isFalse();
assertThat(continuation.context())
        .contains("read_file")
        .contains("/home/gem/a.txt")
        .contains("文件内容");
```

- [ ] **Step 3: 运行测试并确认缺少字段/接口而失败**

Run: `mvn "-Dtest=ConversationContinuationTest" test`

Expected: 编译失败，提示 `runStatus`、`checkpointJson` 或续接接口不存在。

- [ ] **Step 4: 给 `chat_message` 增加可空字段**

```java
@Column(name = "run_status", length = 32)
private String runStatus;

@Column(name = "checkpoint_json", columnDefinition = "LONGTEXT")
private String checkpointJson;
```

- [ ] **Step 5: 增加最近消息查询、暂停保存和新旧数据恢复逻辑**

```java
Optional<ChatMessageEntity> findFirstBySessionIdOrderByTimestampDesc(String sessionId);
```

旧数据识别只接受当前两种明确超限文案且要求 `events_json` 非空，避免普通助手消息被误判。

- [ ] **Step 6: 实现 `AgentContinuationContextFormatter`**

格式保留 plan、thinking/reasoning 和工具名/参数/结果；单个长结果保留头尾，避免旧会话事件一次性撑爆规划器输入。

- [ ] **Step 7: 运行持久化与旧数据兼容测试并确认通过**

Run: `mvn "-Dtest=ConversationContinuationTest" test`

Expected: 全部测试通过。

---

### Task 3: 在新一轮上下文准备阶段恢复检查点

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AgentTurnContext.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AgentTurnContextService.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/AgentTurnContextServiceTest.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/AgentServiceWorkspaceMemoryTest.java`

**Interfaces:**
- Consumes: `ConversationService.getLatestContinuation(sessionId)`
- Produces: `AgentTurnContext.history()` 为 checkpoint 历史或最近 20 条普通历史
- Produces: `AgentTurnContext.continuationContext()`

- [ ] **Step 1: 写失败测试，验证精确 checkpoint 优先于最近 20 条历史**

```java
when(conversationService.getLatestContinuation(sessionId)).thenReturn(exactContinuation);

AgentTurnContext context = service.prepare(session, "继续", true);

assertThat(context.history()).containsExactlyElementsOf(checkpointMessages);
assertThat(context.plannerSessionContext()).contains("上轮达到执行上限");
assertThat(context.systemPrompt()).contains("上轮达到执行上限");
```

- [ ] **Step 2: 写失败测试，验证旧 `events_json` 续接时移除最后一条空洞超限提示**

- [ ] **Step 3: 运行测试并确认恢复逻辑尚未接入而失败**

Run: `mvn "-Dtest=AgentTurnContextServiceTest,AgentServiceWorkspaceMemoryTest" test`

- [ ] **Step 4: 在用户消息入库前加载 continuation**

精确 checkpoint：替换执行历史；旧事件：保留最近历史但移除最后一条已识别超限提示。两种路径都把续接资料注入 planner 和 executor 动态上下文。

- [ ] **Step 5: 运行上下文测试并确认通过**

Run: `mvn "-Dtest=AgentTurnContextServiceTest,AgentServiceWorkspaceMemoryTest" test`

---

### Task 4: 超限分支保存检查点并调整 200K/200 边界

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/model/llm/AgentResponse.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/ReactAgentMaxIterationsTest.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/ReactAgentToolCallBoundaryTest.java`

**Interfaces:**
- Produces: `AgentResponse.getRunStatus()`
- Produces: `AgentResponse.getCheckpointMessages()`
- Consumes: 暂停保存 overload

- [ ] **Step 1: 修改最大迭代测试的期望为第 200 轮，并要求保存 checkpoint**

```java
assertThat(eventsCaptor.getValue()).anySatisfy(event ->
        assertThat(event).containsEntry("content", "第 200 轮推理"));
verify(conversationService).addAssistantMessage(
        eq("session-1"), anyString(), isNull(), anyList(),
        eq(AgentRunStatus.PAUSED_MAX_ITERATIONS), anyList());
```

- [ ] **Step 2: 修改压缩测试，使 200K token 以内不压缩、超过 200K 才压缩**

- [ ] **Step 3: 运行测试并确认仍使用 25/24K 且不保存 checkpoint，因此失败**

Run: `mvn "-Dtest=ReactAgentMaxIterationsTest,ReactAgentToolCallBoundaryTest" test`

- [ ] **Step 4: 修改常量并取消二次 20 条裁剪**

```java
private static final int MAX_ITERATIONS = 200;
private static final int SUMMARIZE_THRESHOLD = 200_000;
```

数据库普通历史仍由 `getRecentHistory(sessionId, 20)` 限制；`ReactAgent` 不再二次按条数裁掉 checkpoint。

- [ ] **Step 5: 同步和流式超限分支保存 `PAUSED_MAX_ITERATIONS` 与当前 messages 快照**

检查点必须在追加超限展示文案之前捕获，确保下一轮模型不会把超限提示当作任务事实。

- [ ] **Step 6: 恢复已有压缩摘要到 `conversationSummary`**

若 checkpoint 首部已有 `[Compacted conversation summary]`，新 ReactAgent 初始化时提取摘要，后续再次压缩时合并而不是丢失旧摘要。

- [ ] **Step 7: 运行边界测试并确认通过**

Run: `mvn "-Dtest=ReactAgentMaxIterationsTest,ReactAgentToolCallBoundaryTest" test`

---

### Task 5: ADR、文档和回归验证

**Files:**
- Modify: `docs/project-spec.md`
- Modify: `docs/project-config-reference.md`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Documents: `PAUSED_MAX_ITERATIONS`、`checkpoint_json`、旧事件兜底、200K/200 边界

- [ ] **Step 1: 在第八章新增 ADR，明确展示事件与执行检查点分层**

ADR 必须说明：为何不直接重放 `events_json`；为何复用 `chat_message`；为何 checkpoint 恢复优先于普通最近 20 条历史。

- [ ] **Step 2: 更新配置参考中的模型、迭代和压缩阈值**

- [ ] **Step 3: 运行针对性测试**

Run:

```powershell
mvn "-Dtest=AgentRunCheckpointCodecTest,ConversationContinuationTest,AgentTurnContextServiceTest,AgentServiceWorkspaceMemoryTest,ReactAgentMaxIterationsTest,ReactAgentToolCallBoundaryTest,AgentConfigPropertiesVisionTest" test
```

Expected: 相关测试全部通过，无 failure/error。

- [ ] **Step 4: 运行编译和格式检查**

Run:

```powershell
mvn "-DskipTests" compile
git diff --check
```

Expected: Maven `BUILD SUCCESS`，`git diff --check` 无输出。

- [ ] **Step 5: 检查最终变更范围和敏感信息**

Run:

```powershell
git status --short
git diff --stat
rg -n "deepseek-v4-flash|MAX_ITERATIONS = 25|SUMMARIZE_THRESHOLD = 24_000" src/main docs
```

Expected: 不包含新密钥/Token；配置中不再残留 DeepSeek flash；旧阈值常量不再出现。
