package com.example.sandbox.web.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LLM 协议级消息
 *
 * <p>对应 OpenAI API 中 messages[] 的完整字段，比 {@code ChatMessage} 更丰富：</p>
 * <ul>
 *   <li>支持 tool_calls（assistant 角色携带工具调用）</li>
 *   <li>支持 tool_call_id（tool 角色消息关联工具调用）</li>
 *   <li>支持 reasoningContent（思考链内容）</li>
 *   <li>支持 contentParts（多模态内容，图文混合）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {

    private String role;

    /** 纯文本内容；与 contentParts 互斥，有 contentParts 时本字段忽略。 */
    private String content;

    private String reasoningContent;
    private List<LlmToolCall> toolCalls;
    private String toolCallId;

    /**
     * 多模态内容块列表（OpenAI vision 格式）。
     * 非 null 时 {@link #toApiFormat()} 将 content 序列化为数组而非字符串。
     * 每个元素格式：
     * <pre>
     * {"type": "text",      "text": "..."}
     * {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
     * </pre>
     */
    private List<Map<String, Object>> contentParts;

    // ==================== 工厂方法 ====================

    public static LlmMessage system(String content) {
        return builder().role("system").content(content).build();
    }

    public static LlmMessage user(String content) {
        return builder().role("user").content(content).build();
    }

    /**
     * 构造携带图片的 user 消息（多模态）。
     *
     * @param text       文字说明，可为空字符串
     * @param imageBytes 图片原始字节（PNG / JPG 等）
     * @param mimeType   图片 MIME 类型，如 "image/png"
     * @return 多模态 user 消息
     */
    public static LlmMessage userWithImage(String text, byte[] imageBytes, String mimeType) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        List<Map<String, Object>> parts = new java.util.ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(Map.of("type", "text", "text", text));
        }
        parts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mimeType + ";base64," + b64)
        ));
        return builder().role("user").contentParts(parts).build();
    }

    public static LlmMessage assistant(String content) {
        return builder().role("assistant").content(content).build();
    }

    public static LlmMessage assistant(String content, String reasoningContent) {
        return builder().role("assistant").content(content).reasoningContent(reasoningContent).build();
    }

    public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls) {
        return builder().role("assistant").toolCalls(toolCalls).build();
    }

    public static LlmMessage toolResult(String toolCallId, String content) {
        return builder().role("tool").toolCallId(toolCallId).content(content).build();
    }

    // ==================== 序列化为 API 格式 ====================

    /**
     * 转换为 OpenAI API 的 messages[] 格式。
     * contentParts 非 null 时 content 字段输出为数组（vision 格式），否则输出为字符串。
     */
    public Map<String, Object> toApiFormat() {
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("role", role);

        if (contentParts != null && !contentParts.isEmpty()) {
            // 多模态：content 为数组
            msg.put("content", contentParts);
        } else if (content != null) {
            msg.put("content", content);
        }

        if (reasoningContent != null) {
            msg.put("reasoning_content", reasoningContent);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls.stream().map(tc -> {
                Map<String, Object> tcMap = new java.util.LinkedHashMap<>();
                tcMap.put("id", tc.id());
                tcMap.put("type", "function");
                tcMap.put("function", Map.of(
                        "name", tc.name(),
                        "arguments", tc.arguments() != null ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(tc.arguments()).toString() : "{}"
                ));
                return tcMap;
            }).toList());
        }
        if (toolCallId != null) {
            msg.put("tool_call_id", toolCallId);
        }
        return msg;
    }
}
