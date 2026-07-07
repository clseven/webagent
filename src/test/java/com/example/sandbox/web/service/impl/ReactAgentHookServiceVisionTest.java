package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.tool.ImageBuffer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReactAgentHookServiceVisionTest {

    @Test
    void viewImageHook应调用Agnes并向主Agent注入文本观察结果() {
        ImageBuffer imageBuffer = new ImageBuffer();
        FakeVisionLlm visionLlm = new FakeVisionLlm("画面中有一个登录页，包含用户名和密码输入框。");
        ReactAgentHookService hookService = new ReactAgentHookService(
                imageBuffer, visionLlm, new AgentTodoService(),
                null, new FileCognitionState(), new AgentConfigProperties());

        imageBuffer.put("session-1", "/home/gem/login.png", new byte[]{1, 2, 3}, "image/png");

        ChatMessage injected = hookService.viewImageHook().run(
                new LlmToolCall("call-1", "view_image", Map.of("path", "/home/gem/login.png")),
                "图片已加载（3 bytes），正在分析：/home/gem/login.png",
                "session-1"
        );

        assertThat(injected).isNotNull();
        assertThat(injected.getRole()).isEqualTo("user");
        assertThat(injected.getContent()).contains("/home/gem/login.png");
        assertThat(injected.getContent()).contains("画面中有一个登录页");
        assertThat(injected.getContentParts()).isNull();
        assertThat(visionLlm.systemPrompt.get()).contains("视觉观察器");
        assertThat(visionLlm.messages.get()).hasSize(1);
        assertThat(visionLlm.messages.get().get(0).getContentParts()).isNotNull();
    }

    private static final class FakeVisionLlm implements LlmService {
        private final String response;
        private final AtomicReference<String> systemPrompt = new AtomicReference<>();
        private final AtomicReference<List<ChatMessage>> messages = new AtomicReference<>();

        private FakeVisionLlm(String response) {
            this.response = response;
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
            this.systemPrompt.set(systemPrompt);
            this.messages.set(messages);
            return LlmResponse.text(response);
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
        public Flux<LlmStreamChunk> chatWithToolsStream(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException();
        }
    }
}
