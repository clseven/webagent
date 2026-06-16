package com.example.sandbox.aio.core;

/**
 * 表示调用 AIO Sandbox REST API 时发生的传输、协议或响应解析错误。
 *
 * <p>该异常只描述基础设施调用失败，不负责转换为 Agent 可读文案。</p>
 */
public class AioApiException extends RuntimeException {

    /**
     * 使用错误消息创建 AIO API 异常。
     *
     * @param message 描述失败接口或失败原因的消息
     */
    public AioApiException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和原始异常创建 AIO API 异常。
     *
     * @param message 描述失败接口或失败原因的消息
     * @param cause   原始异常
     */
    public AioApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
