package com.example.sandbox.web.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 服务层响应
 *
 * <p>对 {@link LlmCompletionResponse} 的业务层抽象，供 Agent 层消费。</p>
 * <p>支持 reasoningContent 字段展示思考链。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String content;
    private String reasoningContent;
    private LlmToolCall toolCall;
    private boolean finished;
    private LlmUsage tokenUsage;

    // ==================== 工厂方法 ====================

    public static LlmResponse text(String content) {
        return builder().content(content).finished(true).build();
    }

    public static LlmResponse text(String content, LlmUsage tokenUsage) {
        return builder().content(content).finished(true).tokenUsage(tokenUsage).build();
    }

    public static LlmResponse text(String content, String reasoningContent, LlmUsage tokenUsage) {
        return builder().content(content).reasoningContent(reasoningContent).finished(true).tokenUsage(tokenUsage).build();
    }

    public static LlmResponse toolCall(LlmToolCall toolCall, String thinking) {
        return builder().content(thinking).toolCall(toolCall).finished(false).build();
    }

    public static LlmResponse toolCall(LlmToolCall toolCall, String thinking, LlmUsage tokenUsage) {
        return builder().content(thinking).toolCall(toolCall).finished(false).tokenUsage(tokenUsage).build();
    }

    public static LlmResponse toolCall(LlmToolCall toolCall, String thinking, String reasoningContent, LlmUsage tokenUsage) {
        return builder().content(thinking).reasoningContent(reasoningContent).toolCall(toolCall).finished(false).tokenUsage(tokenUsage).build();
    }

    public boolean hasToolCall() {
        return toolCall != null;
    }
}
