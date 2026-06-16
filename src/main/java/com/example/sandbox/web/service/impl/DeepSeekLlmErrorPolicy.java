package com.example.sandbox.web.service.impl;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * DeepSeek executor 专属错误策略。
 *
 * <p>400、401、402、422、429 直接向用户说明原因且不重试；
 * 500、503 和尚未收到响应的网络故障最多重试两次。</p>
 */
final class DeepSeekLlmErrorPolicy implements LlmErrorPolicy {

    /**
     * 将 DeepSeek 官方 HTTP 错误码映射为用户可理解的中文提示。
     *
     * @param statusCode  HTTP 状态码
     * @param responseBody DeepSeek 原始响应体，仅保留用于日志
     * @return 带状态码的 DeepSeek 调用异常
     */
    @Override
    public LlmApiException httpError(int statusCode, String responseBody) {
        return new LlmApiException(statusCode, switch (statusCode) {
            case 400 -> "DeepSeek 请求格式错误，请联系管理员检查请求配置。";
            case 401 -> "DeepSeek API Key 无效，请联系管理员检查配置。";
            case 402 -> "DeepSeek 账户余额不足，请充值后重试。";
            case 422 -> "DeepSeek 请求参数无效，请联系管理员检查模型或参数配置。";
            case 429 -> "DeepSeek 请求过于频繁，请稍后再试。";
            case 500 -> "DeepSeek 服务暂时异常，请稍后再试。";
            case 503 -> "DeepSeek 服务当前繁忙，请稍后再试。";
            default -> "DeepSeek 服务请求失败（HTTP " + statusCode + "），请稍后再试。";
        });
    }

    /**
     * 判断异常是否属于临时故障。
     *
     * <p>429 明确不重试，避免限流期间继续增加请求压力。</p>
     *
     * @param error 原始异常
     * @return 仅 500、503、网络连接和超时错误返回 true
     */
    @Override
    public boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof LlmApiException apiException) {
                Integer statusCode = apiException.getStatusCode();
                return statusCode != null && (statusCode == 500 || statusCode == 503);
            }
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 设置最多重试两次，即一次原请求加两次重试。
     *
     * @return 2
     */
    @Override
    public int maxRetries() {
        return 2;
    }

    /**
     * DeepSeek 错误需要传到 Agent/SSE 层，避免伪装为正常模型回答。
     *
     * @return true
     */
    @Override
    public boolean propagateErrors() {
        return true;
    }

    /**
     * 将网络异常和未知异常转换为稳定的用户提示。
     *
     * @param error 原始异常
     * @return 可展示给用户的 DeepSeek 调用异常
     */
    @Override
    public RuntimeException normalize(Throwable error) {
        if (error instanceof LlmApiException apiException) {
            return apiException;
        }
        if (isRetryable(error)) {
            return new LlmApiException(null, "无法连接 DeepSeek 服务，请稍后再试。", error);
        }
        return new LlmApiException(null, "DeepSeek 调用失败，请稍后再试。", error);
    }

    /**
     * 将 DeepSeek 非正常结束原因转换为明确错误。
     *
     * @param finishReason DeepSeek 返回的 finish_reason
     * @return 异常结束时返回错误，stop/tool_calls 等正常结束返回 null
     */
    @Override
    public LlmApiException finishReasonError(String finishReason) {
        if (finishReason == null || finishReason.isBlank()
                || "stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
            return null;
        }
        return switch (finishReason) {
            case "length" -> new LlmApiException(null,
                    "DeepSeek 输出达到长度限制，请缩短问题或减少上下文后重试。");
            case "content_filter" -> new LlmApiException(null,
                    "DeepSeek 因内容安全限制中止了回答，请调整问题后重试。");
            case "insufficient_system_resource" -> new LlmApiException(null,
                    "DeepSeek 推理资源不足，请稍后再试。");
            default -> null;
        };
    }
}
