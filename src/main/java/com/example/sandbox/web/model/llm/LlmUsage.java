package com.example.sandbox.web.model.llm;

/**
 * Token 消耗统计
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens      总 token 数
 * @param cacheHitTokens   缓存命中 token 数
 */
public record LlmUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int cacheHitTokens
) {

    public LlmUsage {
    }

    public LlmUsage(int promptTokens, int completionTokens, int totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0);
    }

    /**
     * 静态工厂方法
     */
    public static LlmUsage of(int promptTokens, int completionTokens, int totalTokens, int cacheHitTokens) {
        return new LlmUsage(promptTokens, completionTokens, totalTokens, cacheHitTokens);
    }

    /**
     * 累加两次用量
     */
    public LlmUsage add(LlmUsage other) {
        if (other == null) return this;
        return new LlmUsage(
                this.promptTokens + other.promptTokens,
                this.completionTokens + other.completionTokens,
                this.totalTokens + other.totalTokens,
                this.cacheHitTokens + other.cacheHitTokens
        );
    }

    @Override
    public String toString() {
        return "LlmUsage{prompt=" + promptTokens + ", completion=" + completionTokens
                + ", total=" + totalTokens + ", cacheHit=" + cacheHitTokens + '}';
    }
}
