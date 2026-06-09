package com.example.sandbox.web.model.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI /chat/completions 原始响应的完整映射
 *
 * <p>提供从 JSON 响应直接解析的静态方法，替代 {@code BaseLlmServiceImpl} 中的手动 JSON 路径遍历。</p>
 */
public class LlmCompletionResponse {

    private final String id;
    private final String model;
    private final List<LlmChoice> choices;
    private final LlmUsage usage;

    private LlmCompletionResponse(String id, String model, List<LlmChoice> choices, LlmUsage usage) {
        this.id = id;
        this.model = model;
        this.choices = choices;
        this.usage = usage;
    }

    /**
     * 从 JSON 响应解析
     */
    public static LlmCompletionResponse parse(ObjectMapper objectMapper, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String id = root.path("id").asText("");
            String model = root.path("model").asText("");

            // 解析 usage
            LlmUsage usage = parseUsage(root.path("usage"));

            // 解析 choices
            List<LlmChoice> choices = new ArrayList<>();
            JsonNode choicesNode = root.path("choices");
            if (choicesNode.isArray()) {
                for (int i = 0; i < choicesNode.size(); i++) {
                    JsonNode choiceNode = choicesNode.get(i);
                    LlmMessage message = parseMessage(objectMapper, choiceNode.path("message"));
                    String finishReason = choiceNode.path("finish_reason").asText("");
                    choices.add(new LlmChoice(i, message, finishReason));
                }
            }

            return new LlmCompletionResponse(id, model, choices, usage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    private static LlmUsage parseUsage(JsonNode usageNode) {
        if (usageNode.isMissingNode()) return null;
        int promptTokens = usageNode.path("prompt_tokens").asInt(0);
        int completionTokens = usageNode.path("completion_tokens").asInt(0);
        int totalTokens = usageNode.path("total_tokens").asInt(0);
        int cacheHitTokens = usageNode.path("prompt_cache_hit_tokens").asInt(0);
        return new LlmUsage(promptTokens, completionTokens, totalTokens, cacheHitTokens);
    }

    private static LlmMessage parseMessage(ObjectMapper objectMapper, JsonNode messageNode) {
        String role = messageNode.path("role").asText("assistant");
        String content = messageNode.path("content").asText(null);
        String reasoningContent = null;
        JsonNode reasoningNode = messageNode.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
            reasoningContent = reasoningNode.asText();
        }

        List<LlmToolCall> toolCalls = null;
        JsonNode toolCallsNode = messageNode.path("tool_calls");
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            toolCalls = new ArrayList<>();
            for (int i = 0; i < toolCallsNode.size(); i++) {
                JsonNode tcNode = toolCallsNode.get(i);
                String tcId = tcNode.path("id").asText("");
                String tcName = tcNode.path("function").path("name").asText("");
                String argsStr = tcNode.path("function").path("arguments").asText("{}");
                java.util.Map<String, Object> arguments;
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> parsed = objectMapper.readValue(argsStr, java.util.Map.class);
                    arguments = parsed;
                } catch (Exception e) {
                    arguments = java.util.Map.of();
                }
                toolCalls.add(new LlmToolCall(tcId, tcName, arguments));
            }
        }

        var builder = LlmMessage.builder()
                .role(role)
                .content(content)
                .reasoningContent(reasoningContent);
        if (toolCalls != null) {
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取第一个 choice（绝大多数场景只有一个）
     */
    public LlmChoice firstChoice() {
        return choices != null && !choices.isEmpty() ? choices.get(0) : null;
    }

    /**
     * 是否包含工具调用
     */
    public boolean hasToolCalls() {
        LlmChoice choice = firstChoice();
        return choice != null && choice.message() != null
                && choice.message().getToolCalls() != null
                && !choice.message().getToolCalls().isEmpty();
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getModel() { return model; }
    public List<LlmChoice> getChoices() { return choices; }
    public LlmUsage getUsage() { return usage; }
}
