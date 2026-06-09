package com.example.sandbox.web.model.llm;

/**
 * LLM 流式响应块 — 流式调用时的单个数据块
 *
 * <h3>用途</h3>
 * <p>LLM 流式 API 返回的数据块类型：</p>
 * <ul>
 *   <li>token — 普通 token（打字效果）</li>
 *   <li>reasoning — 思考链 token（推理模型）</li>
 *   <li>tool_call — 工具调用（流式累积）</li>
 *   <li>finish — 流结束</li>
 * </ul>
 *
 * @author example
 * @date 2026/06/04
 */
public record LlmStreamChunk(
    String type,
    String content,
    String reasoning,
    LlmToolCall toolCall,
    LlmUsage usage,
    boolean finished
) {
    /**
     * 普通 token 块
     */
    public static LlmStreamChunk token(String content) {
        return new LlmStreamChunk("token", content, null, null, null, false);
    }

    /**
     * 思考链 token 块
     */
    public static LlmStreamChunk reasoning(String content) {
        return new LlmStreamChunk("reasoning", null, content, null, null, false);
    }

    /**
     * 工具调用块（流式累积完成后的最终结果）
     */
    public static LlmStreamChunk toolCall(LlmToolCall toolCall) {
        return new LlmStreamChunk("tool_call", null, null, toolCall, null, false);
    }

    /**
     * 流结束块
     */
    public static LlmStreamChunk finish(LlmUsage usage) {
        return new LlmStreamChunk("finish", null, null, null, usage, true);
    }
}
