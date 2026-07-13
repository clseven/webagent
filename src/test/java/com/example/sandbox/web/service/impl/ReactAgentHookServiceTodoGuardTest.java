package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.model.sse.SseEvent;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.tool.ImageBuffer;
import com.example.sandbox.web.service.tool.TodoWriteTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Hook 装配服务会把最终 Todo 门禁接入 ReactAgent。
 */
class ReactAgentHookServiceTodoGuardTest {

    /**
     * 端到端验证升级后的最终门禁：
     * <ol>
     *   <li>轮 1：模型想收尾，但 guard 未闭环 → 前置硬信号拦截，注入 TodoState 门禁提醒。</li>
     *   <li>轮 2：模型调 todo_write 标 blocked → 工具执行并完成 TodoState 闭环。</li>
     *   <li>轮 3：TodoState 已闭环，模型给出候选答案 → 保存候选并注入证据自检。</li>
     *   <li>轮 4：模型不调用工具而直接收尾 → 判定自检通过，原样返回轮 3 候选。</li>
     * </ol>
     */
    @Test
    void configureForChat门禁闭环后自检并原样放行候选() {
        AgentTodoService todoService = new AgentTodoService();
        todoService.update("session-1", "plan", List.of(
                new AgentTodoItem("guard", "实现最终门禁", AgentTodoStatus.IN_PROGRESS,
                        List.of("未完成时拦截最终回答"), List.of(), null, null, List.of())
        ));
        TodoWriteTool todoWriteTool = new TodoWriteTool(todoService);
        SequenceLlmService llmService = new SequenceLlmService(List.of(
                LlmResponse.text("我已经完成。"),
                LlmResponse.toolCall(new LlmToolCall("call-1", "todo_write", Map.of(
                        "todos", List.of(Map.of(
                                "id", "guard",
                                "title", "实现最终门禁",
                                "status", "blocked",
                                "successSignals", List.of("未完成时拦截最终回答"),
                                "blocker", "需要用户确认后继续"
                        ))
                )), "补充 todo 状态"),
                LlmResponse.text("无法继续：需要用户确认后继续。"),
                LlmResponse.text("这是一份被自检轮重新措辞的内容，不应成为最终答案。")
        ));
        ReactAgent agent = new ReactAgent(llmService, List.of(todoWriteTool), "测试提示", "plan");
        com.example.sandbox.web.config.AgentConfigProperties agentConfig =
                new com.example.sandbox.web.config.AgentConfigProperties();
        agentConfig.getHook().setStateCheckEnabled(false);
        ReactAgentHookService hookService = new ReactAgentHookService(
                new ImageBuffer(), new FakeVisionLlm(), todoService,
                null, new FileCognitionState(), agentConfig);

        hookService.configureForChat(agent, List.of(todoWriteTool), "session-1", "请实现", "plan");
        AgentResponse response = agent.run("session-1", "请实现", List.of());

        assertThat(response.getFinalAnswer()).isEqualTo("无法继续：需要用户确认后继续。");
        assertThat(response.getFinalAnswer()).doesNotContain("重新措辞");
        assertThat(llmService.messagesByRound).hasSize(4);

        // 轮 1：前置硬信号拦截
        assertThat(llmService.messagesByRound.get(1))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("TodoState 最终门禁"));

        // 轮 3 产出候选后，轮 4 只负责自检；自检轮生成的新正文不会覆盖候选。
        assertThat(llmService.messagesByRound.get(3))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("回答前自检"));

        assertThat(todoService.get("session-1")).hasValueSatisfying(state ->
                assertThat(state.getItems().get(0).getStatus()).isEqualTo(AgentTodoStatus.BLOCKED));
    }

    /**
     * 流式路径自检通过后也必须发送首版候选，不能发送自检轮重新生成的正文。
     */
    @Test
    void configureForStream自检通过后原样发送首版候选() {
        AgentTodoService todoService = new AgentTodoService();
        StreamSequenceLlmService llmService = new StreamSequenceLlmService(List.of(
                "首版流式候选答案",
                "自检轮改写答案"
        ));
        ReactAgent agent = new ReactAgent(llmService, List.of(), "测试提示", "plan");
        com.example.sandbox.web.config.AgentConfigProperties agentConfig =
                new com.example.sandbox.web.config.AgentConfigProperties();
        agentConfig.getHook().setStateCheckEnabled(false);
        ReactAgentHookService hookService = new ReactAgentHookService(
                new ImageBuffer(), new FakeVisionLlm(), todoService,
                null, new FileCognitionState(), agentConfig);

        hookService.configureForStream(agent, List.of(), "session-stream", "请回答", "plan");
        List<SseEvent> events = agent.runStream("session-stream", "请回答", List.of())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events)
                .filteredOn(event -> "answer".equals(event.type()))
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("content", "首版流式候选答案"));
        assertThat(events)
                .filteredOn(event -> "token".equals(event.type()))
                .extracting(event -> String.valueOf(event.data().get("content")))
                .doesNotContain("自检轮改写答案");
        assertThat(llmService.messagesByRound).hasSize(2);
        assertThat(llmService.messagesByRound.get(1))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("回答前自检"));
    }

    /**
     * 自检期间选择工具补查时，旧候选必须失效；补查后的新候选通过自检后才可返回。
     */
    @Test
    void 自检调用工具后旧候选失效并验证新候选() {
        AgentTodoService todoService = new AgentTodoService();
        Tool probeTool = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return new ToolDefinition("probe", "补查证据", Map.of("type", "object"), "ALL");
            }

            @Override
            public String execute(String sessionId, Map<String, Object> arguments) {
                return "补查结果：旧结论不准确";
            }
        };
        SequenceLlmService llmService = new SequenceLlmService(List.of(
                LlmResponse.text("旧候选答案"),
                LlmResponse.toolCall(new LlmToolCall("probe-1", "probe", Map.of()), "需要补查"),
                LlmResponse.text("补查后的新候选答案"),
                LlmResponse.text("自检确认通过")
        ));
        ReactAgent agent = new ReactAgent(llmService, List.of(probeTool), "测试提示", "plan");
        com.example.sandbox.web.config.AgentConfigProperties agentConfig =
                new com.example.sandbox.web.config.AgentConfigProperties();
        agentConfig.getHook().setStateCheckEnabled(false);
        ReactAgentHookService hookService = new ReactAgentHookService(
                new ImageBuffer(), new FakeVisionLlm(), todoService,
                null, new FileCognitionState(), agentConfig);

        hookService.configureForChat(agent, List.of(probeTool), "session-probe", "请核实", "plan");
        AgentResponse response = agent.run("session-probe", "请核实", List.of());

        assertThat(response.getFinalAnswer()).isEqualTo("补查后的新候选答案");
        assertThat(response.getFinalAnswer()).doesNotContain("旧候选");
        assertThat(llmService.messagesByRound).hasSize(4);
        assertThat(llmService.messagesByRound.get(3))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("回答前自检"));
    }

    /**
     * 只用于测试的顺序响应 LLM。
     */
    private static final class SequenceLlmService implements LlmService {
        private final List<LlmResponse> responses;
        private final List<List<ChatMessage>> messagesByRound = new ArrayList<>();
        private int index;

        private SequenceLlmService(List<LlmResponse> responses) {
            this.responses = responses;
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResponse chatWithSystemResponse(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
            messagesByRound.add(List.copyOf(messages));
            return responses.get(index++);
        }

        @Override
        public Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<LlmStreamChunk> chatWithToolsStream(String systemPrompt, List<ChatMessage> messages,
                                                        List<ToolDefinition> tools) {
            throw new UnsupportedOperationException();
        }
    }

    /** 只用于流式候选答案验证的顺序响应 LLM。 */
    private static final class StreamSequenceLlmService implements LlmService {
        private final List<String> responses;
        private final List<List<ChatMessage>> messagesByRound = new ArrayList<>();
        private int index;

        private StreamSequenceLlmService(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResponse chatWithSystemResponse(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages,
                                         List<ToolDefinition> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<LlmStreamChunk> chatWithToolsStream(String systemPrompt, List<ChatMessage> messages,
                                                        List<ToolDefinition> tools) {
            messagesByRound.add(List.copyOf(messages));
            String content = responses.get(index++);
            return Flux.just(
                    LlmStreamChunk.token(content),
                    LlmStreamChunk.finish(new LlmUsage(1, 1, 2, 0))
            );
        }
    }

    /**
     * 只用于满足 Hook 服务构造函数的视觉模型。
     */
    private static final class FakeVisionLlm implements LlmService {
        @Override
        public String chat(List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResponse chatWithSystemResponse(String systemPrompt, List<ChatMessage> messages) {
            return LlmResponse.text("视觉观察");
        }

        @Override
        public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<LlmStreamChunk> chatWithToolsStream(String systemPrompt, List<ChatMessage> messages,
                                                        List<ToolDefinition> tools) {
            throw new UnsupportedOperationException();
        }
    }
}
