package com.example.sandbox.web.service.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 DeepSeek 错误码、重试分类和结束原因的纯策略行为。
 */
class DeepSeekLlmErrorPolicyTest {

    /** 被测试的 DeepSeek 错误策略。 */
    private final DeepSeekLlmErrorPolicy policy = new DeepSeekLlmErrorPolicy();

    /** 验证 429 会产生明确提示且不会进入自动重试。 */
    @Test
    void mapsRateLimitToFriendlyNonRetryableError() {
        LlmApiException error = policy.httpError(429, "{\"error\":{\"message\":\"rate limit\"}}");

        assertThat(error.getMessage()).isEqualTo("DeepSeek 请求过于频繁，请稍后再试。");
        assertThat(error.getStatusCode()).isEqualTo(429);
        assertThat(policy.isRetryable(error)).isFalse();
    }

    /** 验证只有 500 和 503 被视为可重试的 HTTP 错误。 */
    @Test
    void retriesOnlyTemporaryServerErrors() {
        assertThat(policy.isRetryable(policy.httpError(500, ""))).isTrue();
        assertThat(policy.isRetryable(policy.httpError(503, ""))).isTrue();
        assertThat(policy.isRetryable(policy.httpError(400, ""))).isFalse();
        assertThat(policy.isRetryable(policy.httpError(401, ""))).isFalse();
        assertThat(policy.isRetryable(policy.httpError(402, ""))).isFalse();
        assertThat(policy.isRetryable(policy.httpError(422, ""))).isFalse();
        assertThat(policy.maxRetries()).isEqualTo(2);
    }

    /** 验证客户端配置类错误会返回可操作的中文提示。 */
    @Test
    void mapsKnownClientErrorsToActionableMessages() {
        assertThat(policy.httpError(400, "").getMessage())
                .isEqualTo("DeepSeek 请求格式错误，请联系管理员检查请求配置。");
        assertThat(policy.httpError(401, "").getMessage())
                .isEqualTo("DeepSeek API Key 无效，请联系管理员检查配置。");
        assertThat(policy.httpError(402, "").getMessage())
                .isEqualTo("DeepSeek 账户余额不足，请充值后重试。");
        assertThat(policy.httpError(422, "").getMessage())
                .isEqualTo("DeepSeek 请求参数无效，请联系管理员检查模型或参数配置。");
    }

    /** 验证连接和超时类故障可重试，并被转换为稳定提示。 */
    @Test
    void retriesNetworkFailuresAndNormalizesTheirMessage() {
        IOException failure = new IOException("connection reset");

        assertThat(policy.isRetryable(failure)).isTrue();
        assertThat(policy.normalize(failure).getMessage())
                .isEqualTo("无法连接 DeepSeek 服务，请稍后再试。");
    }

    /** 验证 DeepSeek 非正常 finish_reason 会转换为明确错误。 */
    @Test
    void mapsAbnormalFinishReasons() {
        assertThat(policy.finishReasonError("length").getMessage())
                .isEqualTo("DeepSeek 输出达到长度限制，请缩短问题或减少上下文后重试。");
        assertThat(policy.finishReasonError("content_filter").getMessage())
                .isEqualTo("DeepSeek 因内容安全限制中止了回答，请调整问题后重试。");
        assertThat(policy.finishReasonError("insufficient_system_resource").getMessage())
                .isEqualTo("DeepSeek 推理资源不足，请稍后再试。");
        assertThat(policy.finishReasonError("stop")).isNull();
        assertThat(policy.finishReasonError("tool_calls")).isNull();
    }
}
