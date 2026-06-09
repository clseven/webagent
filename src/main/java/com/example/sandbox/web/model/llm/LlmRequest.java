package com.example.sandbox.web.model.llm;

import com.example.sandbox.web.model.entity.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

/**
 * LLM API 请求体
 *
 * <p>类型安全的请求模型，替代 {@code Map<String, Object>}。</p>
 * <p>对应 OpenAI /chat/completions 的请求格式。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    @NonNull
    private String model;

    @NonNull
    private List<LlmMessage> messages;

    private List<ToolDefinition> tools;
    private Double temperature;
    private Integer maxTokens;

    /**
     * 转换为 OpenAI API 请求体格式
     */
    public Map<String, Object> toApiFormat() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(LlmMessage::toApiFormat).toList());

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(ToolDefinition::toApiFormat).toList());
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        return body;
    }
}
