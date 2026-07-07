package com.example.sandbox.web.controller;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import com.example.sandbox.web.service.impl.SandboxViewTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * 沙箱视图同源 HTTP 代理控制器。
 *
 * <p>浏览器只访问 `/sandbox-view/{token}/...`，控制器根据短期 token 找到用户，
 * 再按用户读取当前最新 AIO endpoint 并转发请求。该代理不跟随上游重定向，而是把
 * Location 改写回同源路径，避免浏览器跳到 `127.0.0.1` 或随机端口。</p>
 */
@Slf4j
@Controller
public class SandboxViewProxyController {

    /** 不应转发给上游 AIO 服务的 hop-by-hop 请求头。 */
    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of(
            "host",
            "connection",
            "content-length",
            "transfer-encoding",
            "upgrade",
            "forwarded",
            "x-forwarded-host",
            "x-forwarded-prefix",
            "x-forwarded-proto");

    /** 不应直接写回浏览器的 hop-by-hop 响应头。 */
    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of(
            "connection", "transfer-encoding", "content-length");

    /** WebSocket 握手请求头；带有该头的请求应交给 WebSocket 代理处理。 */
    private static final String NON_WEBSOCKET_REQUEST = "!Sec-WebSocket-Key";

    /** 沙箱视图 token 服务。 */
    private final SandboxViewTokenService tokenService;

    /** 沙箱服务，用于按用户查当前 endpoint。 */
    private final SandboxServiceImpl sandboxService;

    /** HTTP 代理客户端。 */
    private final HttpClient httpClient;

    /**
     * 创建沙箱视图代理控制器。
     *
     * @param tokenService 沙箱视图 token 服务
     * @param sandboxService 沙箱服务
     */
    @Autowired
    public SandboxViewProxyController(SandboxViewTokenService tokenService,
                                      SandboxServiceImpl sandboxService) {
        this(tokenService, sandboxService, HttpClient.newBuilder()
                // 强制 HTTP/1.1：默认 HTTP/2 会在明文连接上发起 h2c 升级，附带
                // Upgrade: h2c / HTTP2-Settings 头。沙箱容器内的 nginx 不支持 h2c，
                // 把这些头转发给 code-server 后端会触发 502 Bad Gateway。
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * 创建可注入 HTTP 客户端的代理控制器，便于测试代理行为。
     *
     * @param tokenService 沙箱视图 token 服务
     * @param sandboxService 沙箱服务
     * @param httpClient HTTP 代理客户端
     */
    SandboxViewProxyController(SandboxViewTokenService tokenService,
                               SandboxServiceImpl sandboxService,
                               HttpClient httpClient) {
        this.tokenService = tokenService;
        this.sandboxService = sandboxService;
        this.httpClient = httpClient;
    }

    /**
     * 代理沙箱视图普通 HTTP 请求。
     *
     * @param token 沙箱视图 token
     * @param request 当前浏览器请求
     * @param response 当前浏览器响应
     * @throws IOException 当代理读取或写入失败时抛出
     */
    @RequestMapping(
            value = {"/sandbox-view/{token}", "/sandbox-view/{token}/**"},
            method = {
                    RequestMethod.GET,
                    RequestMethod.POST,
                    RequestMethod.PUT,
                    RequestMethod.DELETE,
                    RequestMethod.PATCH,
                    RequestMethod.HEAD,
                    RequestMethod.OPTIONS
            },
            headers = NON_WEBSOCKET_REQUEST)
    public void proxy(@PathVariable String token,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        var target = tokenService.resolve(token);
        if (target.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String endpoint;
        try {
            endpoint = sandboxService.getAioEndpointForUser(target.get().userId());
        } catch (SessionNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String upstreamPath = SandboxViewProxySupport.upstreamPath(token, request);
        try {
            HttpRequest upstreamRequest = buildUpstreamRequest(endpoint, upstreamPath, token, request);
            HttpResponse<byte[]> upstreamResponse = httpClient.send(
                    upstreamRequest,
                    HttpResponse.BodyHandlers.ofByteArray());
            log.info("沙箱视图 HTTP 代理: token={}, userId={}, endpoint={}, path={}, status={}",
                    token, target.get().userId(), endpoint, upstreamPath, upstreamResponse.statusCode());
            writeResponse(upstreamResponse, response, endpoint, token, upstreamPath, request.getMethod());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("沙箱视图 HTTP 代理被中断: token={}, endpoint={}, path={}",
                    token, endpoint, upstreamPath, e);
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        } catch (Exception e) {
            log.warn("沙箱视图 HTTP 代理失败: token={}, userId={}, endpoint={}, path={}",
                    token, target.get().userId(), endpoint, upstreamPath, e);
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        }
    }

    /**
     * 构造上游 AIO HTTP 请求。
     *
     * @param endpoint AIO endpoint
     * @param upstreamPath AIO 上游路径
     * @param request 当前浏览器请求
     * @return 上游 HTTP 请求
     * @throws IOException 当读取请求体失败时抛出
     */
    private HttpRequest buildUpstreamRequest(String endpoint,
                                             String upstreamPath,
                                             String token,
                                             HttpServletRequest request) throws IOException {
        URI targetUri = URI.create("http://" + endpoint + upstreamPath);
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher bodyPublisher = requestHasBody(request)
                ? HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes())
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri).method(method, bodyPublisher);

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (SKIPPED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
        builder.header("X-Forwarded-Host", forwardedHost(request));
        builder.header("X-Forwarded-Proto", forwardedProto(request));
        builder.header("X-Forwarded-Prefix", SandboxViewProxySupport.proxyPrefix(token));
        return builder.build();
    }

    /**
     * 解析可信的外部访问 Host。
     *
     * <p>优先使用边界反向代理写入的 X-Forwarded-Host；若不存在则回退到浏览器请求的 Host。</p>
     *
     * @param request 当前浏览器请求
     * @return 外部访问 Host
     */
    private String forwardedHost(HttpServletRequest request) {
        String forwardedHost = firstForwardedValue(request.getHeader("X-Forwarded-Host"));
        if (forwardedHost != null) {
            return forwardedHost;
        }
        String host = request.getHeader(HttpHeaders.HOST);
        if (host != null && !host.isBlank()) {
            return host;
        }
        int port = request.getServerPort();
        boolean defaultPort = port == 80 || port == 443;
        return defaultPort ? request.getServerName() : request.getServerName() + ":" + port;
    }

    /**
     * 解析可信的外部访问协议。
     *
     * <p>云端 TLS 通常终止在 Nginx 或负载均衡层，后端看到的 request scheme 可能仍是 http；
     * 因此优先使用 X-Forwarded-Proto，再回退到当前请求安全标记。</p>
     *
     * @param request 当前浏览器请求
     * @return 外部访问协议，通常为 http 或 https
     */
    private String forwardedProto(HttpServletRequest request) {
        String forwardedProto = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        if (forwardedProto != null) {
            return forwardedProto;
        }
        return request.isSecure() ? "https" : "http";
    }

    /**
     * 提取 X-Forwarded-* 头中的第一个有效值。
     *
     * @param value 原始请求头
     * @return 第一个有效值，缺失时返回 null
     */
    private String firstForwardedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.split(",", 2)[0].trim();
        return first.isBlank() ? null : first;
    }

    /**
     * 判断当前请求是否带有需要转发的请求体。
     *
     * @param request 当前浏览器请求
     * @return 有请求体时返回 true
     */
    private boolean requestHasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0
                && !"GET".equalsIgnoreCase(request.getMethod())
                && !"HEAD".equalsIgnoreCase(request.getMethod());
    }

    /**
     * 将上游响应写回浏览器。
     *
     * @param upstreamResponse 上游响应
     * @param response 当前浏览器响应
     * @param endpoint AIO endpoint
     * @param token 沙箱视图 token
     * @param upstreamPath 当前 AIO 上游路径
     * @param method 当前 HTTP 方法
     * @throws IOException 当响应写入失败时抛出
     */
    private void writeResponse(HttpResponse<byte[]> upstreamResponse,
                               HttpServletResponse response,
                               String endpoint,
                               String token,
                               String upstreamPath,
                               String method) throws IOException {
        response.setStatus(upstreamResponse.statusCode());
        upstreamResponse.headers().map().forEach((name, values) -> {
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (SKIPPED_RESPONSE_HEADERS.contains(lowerName)) {
                return;
            }
            for (String value : values) {
                String headerValue = HttpHeaders.LOCATION.equalsIgnoreCase(name)
                        ? SandboxViewProxySupport.rewriteLocation(value, endpoint, token, upstreamPath)
                        : value;
                response.addHeader(name, headerValue);
            }
        });
        if (!response.containsHeader(HttpHeaders.CACHE_CONTROL)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        byte[] body = upstreamResponse.body();
        if (body == null || "HEAD".equalsIgnoreCase(method)) {
            return;
        }
        response.setContentLengthLong(body.length);
        response.getOutputStream().write(body);
    }
}
