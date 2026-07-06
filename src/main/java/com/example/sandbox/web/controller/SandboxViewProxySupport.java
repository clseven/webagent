package com.example.sandbox.web.controller;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Optional;

/**
 * 沙箱视图同源代理的路径工具。
 *
 * <p>HTTP 代理和 WebSocket 代理都需要把 `/sandbox-view/{token}/...`
 * 还原为 AIO 内部路径，并把 AIO 返回的跳转地址改写回同源代理路径。</p>
 */
final class SandboxViewProxySupport {

    /** 浏览器访问沙箱视图时使用的同源代理前缀。 */
    private static final String PROXY_PREFIX = "/sandbox-view/";

    /** 工具类不允许实例化。 */
    private SandboxViewProxySupport() {
    }

    /**
     * 从 Servlet 请求还原上游 AIO 路径。
     *
     * @param token 当前沙箱视图 token
     * @param request 浏览器请求
     * @return AIO 内部路径和查询串
     */
    static String upstreamPath(String token, HttpServletRequest request) {
        return upstreamPath(token, request.getRequestURI(), request.getQueryString());
    }

    /**
     * 从 URI 还原上游 AIO 路径。
     *
     * @param token 当前沙箱视图 token
     * @param uri 浏览器请求 URI
     * @return AIO 内部路径和查询串
     */
    static String upstreamPath(String token, URI uri) {
        return upstreamPath(token, uri.getRawPath(), uri.getRawQuery());
    }

    /**
     * 从请求路径还原上游 AIO 路径。
     *
     * @param token 当前沙箱视图 token
     * @param requestPath 浏览器请求路径
     * @param query 浏览器请求查询串
     * @return AIO 内部路径和查询串
     */
    static String upstreamPath(String token, String requestPath, String query) {
        String prefix = proxyPrefix(token);
        String path = requestPath != null && requestPath.startsWith(prefix)
                ? requestPath.substring(prefix.length())
                : "/";
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    /**
     * 从浏览器路径中提取 token。
     *
     * @param requestPath 浏览器请求路径
     * @return 提取到的 token，不存在时返回空
     */
    static Optional<String> tokenFromPath(String requestPath) {
        if (requestPath == null || !requestPath.startsWith(PROXY_PREFIX)) {
            return Optional.empty();
        }
        String rest = requestPath.substring(PROXY_PREFIX.length());
        int slash = rest.indexOf('/');
        String token = slash >= 0 ? rest.substring(0, slash) : rest;
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    /**
     * 把 AIO 返回的 Location 改写回同源代理路径。
     *
     * @param location AIO 返回的原始 Location
     * @param endpoint 当前 AIO endpoint
     * @param token 当前沙箱视图 token
     * @param currentUpstreamPath 当前请求对应的 AIO 路径
     * @return 改写后的 Location；外部地址或无法解析时返回原值
     */
    static String rewriteLocation(String location,
                                  String endpoint,
                                  String token,
                                  String currentUpstreamPath) {
        if (location == null || location.isBlank()) {
            return location;
        }
        try {
            URI current = URI.create("http://" + endpoint + normalizeUpstreamPath(currentUpstreamPath));
            URI resolved = current.resolve(location);
            if (!endpoint.equals(resolved.getRawAuthority())) {
                return location;
            }
            String path = resolved.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String query = resolved.getRawQuery();
            return proxyPrefix(token) + path + (query == null || query.isBlank() ? "" : "?" + query);
        } catch (IllegalArgumentException e) {
            return location;
        }
    }

    /**
     * 生成同源代理路径前缀。
     *
     * @param token 当前沙箱视图 token
     * @return 代理路径前缀
     */
    static String proxyPrefix(String token) {
        return PROXY_PREFIX + token;
    }

    /**
     * 生成 WebSocket 上游地址。
     *
     * @param endpoint 当前 AIO endpoint
     * @param upstreamPath 当前 AIO 路径
     * @return WebSocket 上游 URI
     */
    static URI websocketUri(String endpoint, String upstreamPath) {
        return URI.create("ws://" + endpoint + normalizeUpstreamPath(upstreamPath));
    }

    /**
     * 确保上游路径以斜杠开头。
     *
     * @param path 上游路径
     * @return 规范化后的上游路径
     */
    private static String normalizeUpstreamPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
