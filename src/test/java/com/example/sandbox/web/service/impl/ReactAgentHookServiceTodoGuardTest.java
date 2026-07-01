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
     * 第一次最终回答应被门禁拦截；模型随后调用 todo_write 标记阻塞后，第二次最终回答才允许返回。
     */
    @Test
    void configureForChat会注册FinalTodoGuardHook() {
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
                LlmResponse.text("无法继续：需要用户确认后继续。")
        ));
        ReactAgent agent = new ReactAgent(llmService, List.of(todoWriteTool), "测试提示", "plan");
        ReactAgentHookService hookService = new ReactAgentHookService(
                new ImageBuffer(), new FakeVisionLlm(), todoService);

        hookService.configureForChat(agent, List.of(todoWriteTool), "session-1", "请实现", "plan");
        AgentResponse response = agent.run("session-1", "请实现", List.of());

        assertThat(response.getFinalAnswer()).contains("需要用户确认");
        assertThat(llmService.messagesByRound).hasSize(3);
        assertThat(llmService.messagesByRound.get(1))
                .extracting(ChatMessage::getContent)
                .anySatisfy(content -> assertThat(content).contains("TodoState 最终门禁"));
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
