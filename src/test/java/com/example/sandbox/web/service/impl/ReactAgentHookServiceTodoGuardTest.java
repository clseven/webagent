package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.LlmService;
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
     *   <li>轮 2：模型调 todo_write 标 blocked → 工具执行，收尾计数被 {@code onToolExecuted} 清零。</li>
     *   <li>轮 3：TodoState 已闭环，模型收尾（计数从 0 起 → 1）→ 注入证据自检提示。</li>
     *   <li>轮 4：模型仍收尾（计数 2）→ 仍注入自检提示。</li>
     *   <li>轮 5：模型仍收尾（计数 3 达阈值）→ 强制放行，返回最终答案。</li>
     * </ol>
     * 同时验证：工具执行清零计数（否则轮 3 的计数会累加、无法在轮 5 精确命中阈值）。
     */
    @Test
    void configureForChat门禁两层把关且工具执行清零收尾计数() {
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
                LlmResponse.text("无法继续：需要用户确认后继续。"),
                LlmResponse.text("无法继续：需要用户确认后继续。")
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

        // 轮 5 达自检阈值后放行，拿到最终答案
        assertThat(response.getFinalAnswer()).contains("需要用户确认");
        assertThat(llmService.messagesByRound).hasSize(5);

        // 轮 1：前置硬信号拦截
        assertThat(llmService.messagesByRound.get(1))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("TodoState 最终门禁"));

        // 轮 3、轮 4：TodoState 闭环后走证据自检（工具执行已清零计数，故这里从第 1 次自检重新计）
        assertThat(llmService.messagesByRound.get(3))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("回答前自检"));
        assertThat(llmService.messagesByRound.get(4))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("回答前自检"));

        assertThat(todoService.get("session-1")).hasValueSatisfying(state ->
                assertThat(state.getItems().get(0).getStatus()).isEqualTo(AgentTodoStatus.BLOCKED));
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
