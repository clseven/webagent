package com.example.sandbox.web.service.mcpclient;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * Streamable HTTP MCP endpoint 解析器。
 *
 * <p>官方 SDK 要求分别提供 base URI 和 endpoint。本类把用户给出的完整 URL
 * 精确拆分为这两部分，不猜测 {@code /mcp}，也不修改路径、末尾斜杠或查询参数。</p>
 */
@Component
public class McpEndpointResolver {

    /**
     * 解析并规范化完整 MCP endpoint URL。
     *
     * <p>空路径按 HTTP 根路径 {@code /} 处理；fragment 不会发送给服务器，因此直接拒绝，
     * 避免配置文本与实际请求地址不一致。</p>
     *
     * @param url 用户或系统配置的完整 MCP endpoint URL
     * @return 可直接交给 SDK 的 base URI、endpoint 和规范化 URL
     * @throws IllegalArgumentException URL 不是有效的绝对 HTTP(S) 地址时抛出
     */
    public McpResolvedEndpoint resolve(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MCP URL 不能为空");
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MCP URL 格式无效", e);
        }

        String scheme = uri.getScheme() != null
                ? uri.getScheme().toLowerCase(Locale.ROOT)
                : "";
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("MCP URL 必须使用 http 或 https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("MCP URL 缺少有效主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("MCP URL 不允许包含用户名或密码");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP URL 不允许包含 fragment");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String authorityHost = host.contains(":") ? "[" + host + "]" : host;
        int port = normalizePort(scheme, uri.getPort());
        String baseUri = scheme + "://" + authorityHost
                + (port >= 0 ? ":" + port : "");

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String endpoint = uri.getRawQuery() == null
                ? path
                : path + "?" + uri.getRawQuery();
        return new McpResolvedEndpoint(baseUri, endpoint, baseUri + endpoint);
    }

    /**
     * 返回适合比较的完整 URL。
     *
     * @param url 原始 MCP URL
     * @return 仅规范化协议、主机、默认端口和空根路径后的 URL
     */
    public String normalize(String url) {
        return resolve(url).normalizedUrl();
    }

    /**
     * 移除协议默认端口，保留其他显式端口。
     *
     * @param scheme URL 协议
     * @param port   URI 解析出的端口
     * @return 非默认端口；没有端口或属于默认端口时返回 -1
     */
    private int normalizePort(String scheme, int port) {
        if (port < 0 || "https".equals(scheme) && port == 443
                || "http".equals(scheme) && port == 80) {
            return -1;
        }
        return port;
    }
}
