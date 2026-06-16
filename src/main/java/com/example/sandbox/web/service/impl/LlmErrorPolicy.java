package com.example.sandbox.web.service.impl;

/**
 * LLM 厂商错误处理扩展点。
 *
 * <p>基类通过该策略完成状态码映射、重试判断、异常规范化和结束原因校验。
 * 默认策略保持原有行为；只有显式传入厂商策略的实现才会启用额外处理。</p>
 */
interface LlmErrorPolicy {

    /** 默认策略：不重试、不向上抛出厂商错误，保持规划模型现有行为。 */
    LlmErrorPolicy DEFAULT = (statusCode, responseBody) -> new IllegalStateException(
            "LLM API returned HTTP " + statusCode + ": " + responseBody);

    /**
     * 将非 2xx HTTP 响应转换为异常。
     *
     * @param statusCode  HTTP 状态码
     * @param responseBody 上游原始响应体
     * @return 可供后续重试和展示使用的异常
     */
    RuntimeException httpError(int statusCode, String responseBody);

    /**
     * 判断失败是否允许自动重试。
     *
     * @param error 当前失败
     * @return true 表示允许重试
     */
    default boolean isRetryable(Throwable error) {
        return false;
    }

    /**
     * 获取最大重试次数，不包含首次请求。
     *
     * @return 最大重试次数
     */
    default int maxRetries() {
        return 0;
    }

    /**
     * 判断同步调用是否应将异常继续抛给 Agent 层。
     *
     * @return true 表示抛出，false 表示沿用基类兜底文本
     */
    default boolean propagateErrors() {
        return false;
    }

    /**
     * 将底层异常转换为稳定、可读的业务异常。
     *
     * @param error 原始异常
     * @return 规范化后的运行时异常
     */
    default RuntimeException normalize(Throwable error) {
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(error);
    }

    /**
     * 校验模型返回的 finish_reason。
     *
     * @param finishReason 模型结束原因
     * @return 异常结束时返回异常，正常结束时返回 null
     */
    default RuntimeException finishReasonError(String finishReason) {
        return null;
    }
}
