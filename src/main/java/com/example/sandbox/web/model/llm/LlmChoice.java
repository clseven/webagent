package com.example.sandbox.web.model.llm;

/**
 * LLM 响应中的单个选项
 *
 * <p>对应 OpenAI API 响应中 choices[] 的单个元素。</p>
 *
 * @param index        选项索引
 * @param message      响应消息
 * @param finishReason 结束原因：stop / tool_calls / length / content_filter
 */
public record LlmChoice(
        int index,
        LlmMessage message,
        String finishReason
) {
}
