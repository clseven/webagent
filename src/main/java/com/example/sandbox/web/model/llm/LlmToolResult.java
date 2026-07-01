package com.example.sandbox.web.model.llm;

/**
 * 工具执行结果。
 *
 * @param toolCallId    关联的工具调用 ID
 * @param toolName      工具名称
 * @param content       工具执行结果内容
 * @param success       是否执行成功
 * @param durationMs    执行耗时，单位毫秒
 * @param displayReason 工具调用前展示给用户的公开行动说明
 */
public record LlmToolResult(
        String toolCallId,
        String toolName,
        String content,
        boolean success,
        long durationMs,
        String displayReason
) {

    /**
     * 创建成功的工具结果。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName 工具名称
     * @param content 工具结果内容
     * @param durationMs 执行耗时，单位毫秒
     * @return 成功工具结果
     */
    public static LlmToolResult success(String toolCallId, String toolName, String content, long durationMs) {
        return success(toolCallId, toolName, content, durationMs, null);
    }

    /**
     * 创建带公开行动说明的成功工具结果。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName 工具名称
     * @param content 工具结果内容
     * @param durationMs 执行耗时，单位毫秒
     * @param displayReason 工具调用前展示给用户的公开行动说明
     * @return 成功工具结果
     */
    public static LlmToolResult success(String toolCallId, String toolName, String content,
                                        long durationMs, String displayReason) {
        return new LlmToolResult(toolCallId, toolName, content, true, durationMs, displayReason);
    }

    /**
     * 创建失败的工具结果。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName 工具名称
     * @param errorMessage 错误信息
     * @param durationMs 执行耗时，单位毫秒
     * @return 失败工具结果
     */
    public static LlmToolResult failure(String toolCallId, String toolName, String errorMessage, long durationMs) {
        return new LlmToolResult(toolCallId, toolName, errorMessage, false, durationMs, null);
    }
}
