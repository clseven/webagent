package com.example.sandbox.web.model.llm;

/**
 * 工具执行结果
 *
 * @param toolCallId 关联的工具调用 ID
 * @param toolName   工具名称
 * @param content    执行结果内容
 * @param success    是否执行成功
 * @param durationMs 执行耗时（毫秒）
 */
public record LlmToolResult(
        String toolCallId,
        String toolName,
        String content,
        boolean success,
        long durationMs
) {

    /**
     * 创建成功的工具结果
     */
    public static LlmToolResult success(String toolCallId, String toolName, String content, long durationMs) {
        return new LlmToolResult(toolCallId, toolName, content, true, durationMs);
    }

    /**
     * 创建失败的工具结果
     */
    public static LlmToolResult failure(String toolCallId, String toolName, String errorMessage, long durationMs) {
        return new LlmToolResult(toolCallId, toolName, errorMessage, false, durationMs);
    }
}
