package com.example.sandbox.web.model.entity;

import com.example.sandbox.web.model.llm.LlmUsage;

/**
 * ReactAgent 执行结果，包含响应内容和累积的 token 用量
 *
 * @deprecated 使用 {@link com.example.sandbox.web.model.llm.AgentResponse} 替代
 */
@Deprecated
public class ReactResult {

    private final String content;
    private final int totalPromptTokens;
    private final int totalCompletionTokens;
    private final int totalCacheHitTokens;
    private final int totalTokens;
    private final int iterations;

    public ReactResult(String content, int totalPromptTokens, int totalCompletionTokens,
                       int totalCacheHitTokens, int totalTokens, int iterations) {
        this.content = content;
        this.totalPromptTokens = totalPromptTokens;
        this.totalCompletionTokens = totalCompletionTokens;
        this.totalCacheHitTokens = totalCacheHitTokens;
        this.totalTokens = totalTokens;
        this.iterations = iterations;
    }

    public String getContent() { return content; }
    public int getTotalPromptTokens() { return totalPromptTokens; }
    public int getTotalCompletionTokens() { return totalCompletionTokens; }
    public int getTotalCacheHitTokens() { return totalCacheHitTokens; }
    public int getTotalTokens() { return totalTokens; }
    public int getIterations() { return iterations; }
}
