package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.AgentRunStatus;
import com.example.sandbox.web.model.sse.SseEvent;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 ReactAgent 达到最大执行轮次时的流式保存行为。
 */
class ReactAgentMaxIterationsTest {

    /**
     * 正常完成时也应按一次用户请求保存一条运行记录。
     */
    @Test
    @DisplayName("同步正常完成时保存单条运行记录")
    void savesSingleRunRecordWhenSynchronousRunCompletes() {
        LlmService llmService = mock(LlmService.class);
        LlmUsage usage = new LlmUsage(3, 2, 5, 1);
        when(llmService.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(com.example.sandbox.web.model.llm.LlmResponse.text(
                        "任务完成", "普通最终推理", usage));
        ReactAgent agent = new ReactAgent(llmService, List.of(), "测试提示", "测试计划");
        AgentRunPersistenceService runPersistenceService = mock(AgentRunPersistenceService.class);
        agent.setAgentRunPersistenceService(runPersistenceService);
        agent.setPersistedUserMessage("执行任务");

        AgentResponse response = agent.run("session-completed", "执行任务", List.of());

        assertThat(response.getFinalAnswer()).isEqualTo("任务完成");
        verify(runPersistenceService).save(
                eq("session-completed"),
                eq("执行任务"),
                eq("测试计划"),
                eq(List.of()),
                eq("任务完成"),
                eq(AgentRunStatus.COMPLETED),
                eq(usage),
                eq(1));
    }

    /**
     * 超限不是致命错误，应保存已经产生的过程并通过正常完成事件收尾。
     */
    @Test
    @DisplayName("流式超限时保存过程事件并以正常完成事件收尾")
    void savesProcessEventsAndCompletesNormallyWhenStreamReachesIterationLimit() {
        LlmService llmService = mock(LlmService.class);
        AtomicInteger round = new AtomicInteger(0);
        when(llmService.chatWithToolsStream(anyString(), anyList(), anyList()))
                .thenAnswer(invocation -> {
                    int currentRound = round.incrementAndGet();
                    return Flux.just(
                            LlmStreamChunk.token("第 " + currentRound + " 轮思考"),
                            LlmStreamChunk.reasoning("第 " + currentRound + " 轮推理"),
                            LlmStreamChunk.toolCall(new LlmToolCall(
                                    "call_" + currentRound,
                                    "noop",
                                    Map.of("round", currentRound))),
                            LlmStreamChunk.finish(new LlmUsage(1, 2, 3, 0))
                    );
                });
        ConversationService conversationService = mock(ConversationService.class);
        ReactAgent agent = new ReactAgent(
                llmService,
                List.of(namedTool("noop")),
                "测试技能提示",
                "测试执行计划",
                conversationService,
                "session-1"
        );
        AgentRunPersistenceService runPersistenceService = mock(AgentRunPersistenceService.class);
        agent.setAgentRunPersistenceService(runPersistenceService);
        agent.setPersistedUserMessage("请持续调用工具");

        List<SseEvent> events = agent.runStream("session-1", "请持续调用工具", List.of())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(SseEvent::type)
                .doesNotContain("error")
                .contains("answer", "done");
        assertThat(events)
                .filteredOn(event -> "tool_call".equals(event.type()))
                .first()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("displayReason", "正在处理当前任务"));
        assertThat(events.get(events.size() - 2).type()).isEqualTo("answer");
        assertThat(events.get(events.size() - 1).type()).isEqualTo("done");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasoningCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> eventsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> checkpointCaptor =
                ArgumentCaptor.forClass((Class) List.class);

        verify(conversationService).addAssistantMessage(
                eq("session-1"),
                contentCaptor.capture(),
                reasoningCaptor.capture(),
                eventsCaptor.capture(),
                eq(AgentRunStatus.PAUSED_MAX_ITERATIONS),
                checkpointCaptor.capture());

        assertThat(contentCaptor.getValue()).contains("最大执行次数");
        assertThat(reasoningCaptor.getValue()).isNull();
        assertThat(eventsCaptor.getValue()).anySatisfy(event ->
                assertThat(event)
                        .containsEntry("type", "reasoning")
                        .containsEntry("content", "第 200 轮推理"));
        assertThat(eventsCaptor.getValue()).anySatisfy(event ->
                assertThat(event)
                        .containsEntry("type", "toolResult")
                        .containsEntry("tool", "noop"));
        assertThat(eventsCaptor.getValue()).anySatisfy(event ->
                assertThat(event)
                        .containsEntry("type", "status")
                        .containsKey("content"));
        assertThat(checkpointCaptor.getValue())
                .filteredOn(message -> "assistant".equals(message.getRole()) && !message.getToolCalls().isEmpty())
                .isNotEmpty()
                .allSatisfy(message -> {
                    assertThat(message.getContent()).startsWith("第 ").endsWith(" 轮思考");
                    assertThat(message.getReasoning()).startsWith("第 ").endsWith(" 轮推理");
                });
        verify(runPersistenceService).save(
                eq("session-1"),
                eq("请持续调用工具"),
                eq("测试执行计划"),
                org.mockito.ArgumentMatchers.argThat(savedSteps -> savedSteps != null && savedSteps.size() == 200),
                eq(contentCaptor.getValue()),
                eq(AgentRunStatus.PAUSED_MAX_ITERATIONS),
                org.mockito.ArgumentMatchers.any(LlmUsage.class),
                eq(200));

    }

    /**
     * 同步执行达到上限时也必须返回可持久化的协议检查点。
     */
    @Test
    @DisplayName("同步超限时返回暂停状态和完整检查点")
    void returnsCheckpointWhenSynchronousRunReachesIterationLimit() {
        LlmService llmService = mock(LlmService.class);
        AtomicInteger round = new AtomicInteger(0);
        when(llmService.chatWithTools(anyString(), anyList(), anyList()))
                .thenAnswer(invocation -> {
                    int currentRound = round.incrementAndGet();
                    return com.example.sandbox.web.model.llm.LlmResponse.toolCall(
                            new LlmToolCall("sync_" + currentRound, "noop", Map.of("round", currentRound)),
                            "第 " + currentRound + " 轮思考",
                            "第 " + currentRound + " 轮推理",
                            new LlmUsage(1, 2, 3, 0));
                });
        ReactAgent agent = new ReactAgent(llmService, List.of(namedTool("noop")));
        AgentRunPersistenceService runPersistenceService = mock(AgentRunPersistenceService.class);
        agent.setAgentRunPersistenceService(runPersistenceService);
        agent.setPersistedUserMessage("持续执行");

        AgentResponse response = agent.run("session-sync", "持续执行", List.of());

        assertThat(response.getIterations()).isEqualTo(200);
        assertThat(response.getRunStatus()).isEqualTo(AgentRunStatus.PAUSED_MAX_ITERATIONS);
        assertThat(response.getCheckpointMessages())
                .isNotEmpty()
                .anyMatch(message -> "tool".equals(message.getRole()));
        assertThat(response.getCheckpointMessages())
                .filteredOn(message -> "assistant".equals(message.getRole()) && !message.getToolCalls().isEmpty())
                .isNotEmpty()
                .allSatisfy(message -> {
                    assertThat(message.getContent()).startsWith("第 ").endsWith(" 轮思考");
                    assertThat(message.getReasoning()).startsWith("第 ").endsWith(" 轮推理");
                });
        verify(runPersistenceService).save(
                eq("session-sync"),
                eq("持续执行"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(savedSteps -> savedSteps != null && savedSteps.size() == 200),
                eq(ConversationServiceImpl.SYNC_ITERATION_LIMIT_MESSAGE),
                eq(AgentRunStatus.PAUSED_MAX_ITERATIONS),
                org.mockito.ArgumentMatchers.any(LlmUsage.class),
                eq(200));
        verify(llmService, atLeastOnce()).chatWithTools(anyString(), anyList(), anyList());
    }

    /**
     * 创建只用于最大轮次测试的轻量工具。
     *
     * @param name 工具名称
     * @return 固定返回空结果的工具实例
     */
    private Tool namedTool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return new ToolDefinition(name, "测试工具", Map.of("type", "object"), "AIO");
            }

            @Override
            public String execute(String sessionId, Map<String, Object> arguments) {
                return "工具结果";
            }
        };
    }
}
