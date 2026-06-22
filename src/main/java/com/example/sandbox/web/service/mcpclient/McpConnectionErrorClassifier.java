package com.example.sandbox.web.service.mcpclient;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP SDK 异常分类器。
 *
 * <p>官方 SDK 会用通用 initialize 异常包装底层 HTTP 或网络错误。本类遍历 cause 链，
 * 提取稳定错误码和有限诊断信息；不会自动重试，因为 initialize 和工具调用的副作用边界不同，
 * 重试应由配置管理流程或用户明确操作触发。</p>
 */
public final class McpConnectionErrorClassifier {

    /** HTTP 状态码匹配器。 */
    private static final Pattern HTTP_STATUS_PATTERN =
            Pattern.compile("(?i)(?:http|status(?: code)?)[^0-9]{0,12}([45]\\d{2})|\\b([45]\\d{2})\\b");

    /** 工具类不允许实例化。 */
    private McpConnectionErrorClassifier() {
    }

    /**
     * 按失败阶段和 cause 链生成结构化错误。
     *
     * @param error 原始异常
     * @param stage 失败阶段
     * @return 可安全展示的 MCP 操作错误
     */
    public static McpOperationError classify(Throwable error, McpConnectionStage stage) {
        String detail = collectDetail(error);
        Integer httpStatus = findHttpStatus(detail);

        if (httpStatus != null) {
            if (httpStatus == 401 || httpStatus == 403) {
                return error(McpErrorCode.AUTH_REQUIRED,
                        "MCP 服务要求认证或当前请求无权限", detail);
            }
            if (httpStatus == 404 || httpStatus == 405) {
                return error(McpErrorCode.ENDPOINT_NOT_FOUND,
                        "MCP endpoint 不存在或不接受 Streamable HTTP 请求", detail);
            }
            if (httpStatus >= 500) {
                return error(McpErrorCode.HTTP_SERVER_ERROR,
                        "MCP 服务端返回错误", detail);
            }
            return error(McpErrorCode.HTTP_CLIENT_ERROR,
                    "MCP 服务拒绝了客户端请求", detail);
        }

        if (hasCause(error, UnknownHostException.class)) {
            return error(McpErrorCode.DNS_FAILED, "无法解析 MCP 服务域名", detail);
        }
        if (hasCause(error, HttpConnectTimeoutException.class)
                || hasCause(error, SocketTimeoutException.class)) {
            return error(McpErrorCode.CONNECT_TIMEOUT, "连接 MCP 服务超时", detail);
        }
        if (hasCause(error, ConnectException.class)) {
            return error(McpErrorCode.CONNECT_REFUSED, "MCP 服务拒绝连接", detail);
        }
        if (hasCause(error, SSLException.class)) {
            return error(McpErrorCode.TLS_FAILED, "MCP HTTPS/TLS 握手失败", detail);
        }
        if (hasCause(error, TimeoutException.class)
                || detail.toLowerCase(Locale.ROOT).contains("timeout")) {
            return error(McpErrorCode.INITIALIZE_TIMEOUT,
                    stage == McpConnectionStage.INITIALIZE
                            ? "MCP initialize 握手超时"
                            : "MCP 工具列表请求超时",
                    detail);
        }
        if (stage != McpConnectionStage.INITIALIZE) {
            return error(McpErrorCode.TOOLS_LIST_FAILED,
                    "MCP 已建立连接，但获取工具列表失败", detail);
        }
        if (looksLikeProtocolError(detail)) {
            return error(McpErrorCode.PROTOCOL_ERROR,
                    "目标地址未返回兼容的 MCP initialize 响应", detail);
        }
        return error(McpErrorCode.UNKNOWN, "MCP 连接初始化失败", detail);
    }

    /**
     * 判断 cause 链中是否包含指定异常。
     *
     * @param error 原始异常
     * @param type  目标异常类型
     * @return 找到目标异常时返回 true
     */
    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 收集有限且去重后的 cause 消息。
     *
     * @param error 原始异常
     * @return 最多六层的错误摘要
     */
    private static String collectDetail(Throwable error) {
        Set<String> messages = new LinkedHashSet<>();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                messages.add(message.trim());
            }
            current = current.getCause();
            depth++;
        }
        String detail = String.join("; ", messages);
        return detail.length() > 1000 ? detail.substring(0, 1000) + "..." : detail;
    }

    /**
     * 从错误文本提取 HTTP 状态码。
     *
     * @param detail cause 链错误摘要
     * @return HTTP 状态码；不存在时返回 null
     */
    private static Integer findHttpStatus(String detail) {
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(detail);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Integer.parseInt(value);
    }

    /**
     * 判断错误是否具有明显协议不兼容特征。
     *
     * @param detail 错误摘要
     * @return 看起来属于协议或响应格式问题时返回 true
     */
    private static boolean looksLikeProtocolError(String detail) {
        String text = detail.toLowerCase(Locale.ROOT);
        return text.contains("json")
                || text.contains("protocol")
                || text.contains("initialize")
                || text.contains("content-type")
                || text.contains("unexpected response");
    }

    /**
     * 创建结构化错误。
     *
     * @param code    错误码
     * @param message 用户说明
     * @param detail  底层摘要
     * @return MCP 操作错误
     */
    private static McpOperationError error(McpErrorCode code, String message, String detail) {
        return new McpOperationError(code, message, detail);
    }
}
