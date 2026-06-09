package com.example.sandbox.web.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {

    private String role;
    private String content;
    private String reasoningContent;
    private List<LlmToolCall> toolCalls;
    private String toolCallId;

    // ==================== 工厂方法 ====================

    public static LlmMessage system(String content) {
        return builder().role("system").content(content).build();
    }

    public static LlmMessage user(String content) {
        return builder().role("user").content(content).build();
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
     * 转换为 OpenAI API 的 messages[] 格式
     */
    public Map<String, Object> toApiFormat() {
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("role", role);
        if (content != null) {
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
