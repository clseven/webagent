package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.model.llm.LlmUsage;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 ReactAgent 达到最大执行轮次时的流式保存行为。
 */
class ReactAgentMaxIterationsTest {

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

        List<SseEvent> events = agent.runStream("session-1", "请持续调用工具", List.of())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(SseEvent::type)
                .doesNotContain("error")
                .contains("answer", "done");
        assertThat(events.get(events.size() - 2).type()).isEqualTo("answer");
        assertThat(events.get(events.size() - 1).type()).isEqualTo("done");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasoningCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> eventsCaptor =
                ArgumentCaptor.forClass((Class) List.class);

        verify(conversationService).addAssistantMessage(
                eq("session-1"),
                contentCaptor.capture(),
                reasoningCaptor.capture(),
                eventsCaptor.capture());

        assertThat(contentCaptor.getValue()).contains("最大执行次数");
        assertThat(reasoningCaptor.getValue()).isNull();
        assertThat(eventsCaptor.getValue()).anySatisfy(event ->
                assertThat(event)
                        .containsEntry("type", "reasoning")
                        .containsEntry("content", "第 25 轮推理"));
        assertThat(eventsCaptor.getValue()).anySatisfy(event ->
                assertThat(event)
                        .containsEntry("type", "toolResult")
                        .containsEntry("tool", "noop"));
        assertThat(eventsCaptor.getValue()).anySatisfy(event ->
                assertThat(event)
                        .containsEntry("type", "status")
                        .containsKey("content"));
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
