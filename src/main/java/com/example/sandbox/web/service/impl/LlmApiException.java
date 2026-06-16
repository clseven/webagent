package com.example.sandbox.web.service.impl;

/**
 * LLM API 调用异常。
 *
 * <p>除用户可读错误消息外，还保留上游 HTTP 状态码，供重试策略判断失败类型。
 * 网络异常等没有 HTTP 响应的情况，状态码为 {@code null}。</p>
 */
public class LlmApiException extends RuntimeException {

    /** 上游 HTTP 状态码；网络错误等无响应场景为 null。 */
    private final Integer statusCode;

    /**
     * 创建不包含底层原因的 LLM API 异常。
     *
     * @param statusCode 上游 HTTP 状态码，可以为 null
     * @param message    可直接展示给用户的错误消息
     */
    public LlmApiException(Integer statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * 创建包含底层原因的 LLM API 异常。
     *
     * @param statusCode 上游 HTTP 状态码，可以为 null
     * @param message    可直接展示给用户的错误消息
     * @param cause      原始网络或调用异常
     */
    public LlmApiException(Integer statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * 获取上游 HTTP 状态码。
     *
     * @return HTTP 状态码；没有收到 HTTP 响应时返回 null
     */
    public Integer getStatusCode() {
        return statusCode;
    }
}
