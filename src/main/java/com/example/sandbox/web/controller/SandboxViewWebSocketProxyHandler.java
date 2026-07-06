package com.example.sandbox.web.controller;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import com.example.sandbox.web.service.impl.SandboxViewTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 沙箱视图 WebSocket 同源代理处理器。
 *
 * <p>code-server、noVNC 和 Web Terminal 都会通过当前 HTTP 路径发起 Upgrade 请求。
 * 该处理器复用 token 到用户的映射，并在握手时按用户查当前 AIO endpoint，然后把浏览器
 * WebSocket 与 AIO 内部 WebSocket 双向桥接。</p>
 */
@Slf4j
@Component
public class SandboxViewWebSocketProxyHandler implements WebSocketHandler {

    /** 上游握手最长等待时间。 */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

    /** WebSocket 握手中不应透传的浏览器请求头。 */
    private static final Set<String> SKIPPED_HANDSHAKE_HEADERS = Set.of(
            "host",
            "connection",
            "upgrade",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions");

    /** WebSocket 子协议请求头名称。 */
    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    /** 沙箱视图 token 服务。 */
    private final SandboxViewTokenService tokenService;

    /** 沙箱服务，用于按用户查当前 endpoint。 */
    private final SandboxServiceImpl sandboxService;

    /** 上游 WebSocket 客户端。 */
    private final WebSocketClient webSocketClient;

    /** 下游会话 ID 到桥接状态的映射。 */
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();

    /**
     * 创建沙箱视图 WebSocket 代理处理器。
     *
     * @param tokenService 沙箱视图 token 服务
     * @param sandboxService 沙箱服务
     */
    @Autowired
    public SandboxViewWebSocketProxyHandler(SandboxViewTokenService tokenService,
                                            SandboxServiceImpl sandboxService) {
        this(tokenService, sandboxService, new StandardWebSocketClient());
    }

    /**
     * 创建可注入客户端的 WebSocket 代理处理器，便于测试握手行为。
     *
     * @param tokenService 沙箱视图 token 服务
     * @param sandboxService 沙箱服务
     * @param webSocketClient 上游 WebSocket 客户端
     */
    SandboxViewWebSocketProxyHandler(SandboxViewTokenService tokenService,
                                     SandboxServiceImpl sandboxService,
                                     WebSocketClient webSocketClient) {
        this.tokenService = tokenService;
        this.sandboxService = sandboxService;
        this.webSocketClient = webSocketClient;
    }

    /**
     * 建立浏览器到 AIO 的 WebSocket 桥接。
     *
     * @param downstream 浏览器侧 WebSocket 会话
     * @throws Exception 当握手或桥接失败时抛出
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession downstream) throws Exception {
        URI requestUri = downstream.getUri();
        String requestPath = requestUri != null ? requestUri.getRawPath() : "";
        var token = SandboxViewProxySupport.tokenFromPath(requestPath);
        if (token.isEmpty()) {
            downstream.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        var target = tokenService.resolve(token.get());
        if (target.isEmpty()) {
            downstream.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String endpoint;
        try {
            endpoint = sandboxService.getAioEndpointForUser(target.get().userId());
        } catch (SessionNotFoundException e) {
            downstream.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String upstreamPath = SandboxViewProxySupport.upstreamPath(token.get(), requestUri);
        URI upstreamUri = SandboxViewProxySupport.websocketUri(endpoint, upstreamPath);
        Bridge bridge = new Bridge(downstream);
        bridges.put(downstream.getId(), bridge);

        try {
            webSocketClient.execute(new UpstreamHandler(bridge), handshakeHeaders(downstream), upstreamUri)
                    .get(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            log.info("沙箱视图 WebSocket 代理已连接: token={}, userId={}, endpoint={}, path={}",
                    token.get(), target.get().userId(), endpoint, upstreamPath);
        } catch (Exception e) {
            bridges.remove(downstream.getId());
            log.warn("沙箱视图 WebSocket 上游握手失败: token={}, userId={}, endpoint={}, path={}",
                    token.get(), target.get().userId(), endpoint, upstreamPath, e);
            downstream.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 将浏览器消息转发到 AIO 上游。
     *
     * @param downstream 浏览器侧 WebSocket 会话
     * @param message 浏览器消息
     * @throws Exception 当转发失败时抛出
     */
    @Override
    public void handleMessage(WebSocketSession downstream, WebSocketMessage<?> message) throws Exception {
        Bridge bridge = bridges.get(downstream.getId());
        if (bridge == null || bridge.upstream.get() == null) {
            downstream.close(CloseStatus.SERVER_ERROR);
            return;
        }
        bridge.sendToUpstream(copyMessage(message));
    }

    /**
     * 处理浏览器侧传输错误。
     *
     * @param downstream 浏览器侧 WebSocket 会话
     * @param exception 传输异常
     */
    @Override
    public void handleTransportError(WebSocketSession downstream, Throwable exception) {
        Bridge bridge = bridges.remove(downstream.getId());
        if (bridge != null) {
            bridge.closeBoth(CloseStatus.SERVER_ERROR);
        }
        log.warn("沙箱视图 WebSocket 浏览器侧传输失败: session={}", downstream.getId(), exception);
    }

    /**
     * 浏览器侧关闭时同步关闭上游连接。
     *
     * @param downstream 浏览器侧 WebSocket 会话
     * @param closeStatus 关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession downstream, CloseStatus closeStatus) {
        Bridge bridge = bridges.remove(downstream.getId());
        if (bridge != null) {
            bridge.closeBoth(closeStatus);
        }
    }

    /**
     * 当前处理器不支持部分消息，因为 AIO 视图通道应以完整消息转发。
     *
     * @return false
     */
    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * 复制可安全转发给上游的握手头。
     *
     * @param downstream 浏览器侧 WebSocket 会话
     * @return 上游握手头
     */
    private WebSocketHttpHeaders handshakeHeaders(WebSocketSession downstream) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        HttpHeaders source = downstream.getHandshakeHeaders();
        source.forEach((name, values) -> {
            if (SKIPPED_HANDSHAKE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            headers.addAll(name, values);
        });
        List<String> protocols = source.get(SEC_WEBSOCKET_PROTOCOL);
        if (protocols != null && !protocols.isEmpty()) {
            headers.put(SEC_WEBSOCKET_PROTOCOL, protocols);
        }
        return headers;
    }

    /**
     * 复制 WebSocket 消息，避免同一个消息对象跨两个会话复用导致缓冲区状态异常。
     *
     * @param message 原始消息
     * @return 可转发消息
     */
    private WebSocketMessage<?> copyMessage(WebSocketMessage<?> message) {
        if (message instanceof TextMessage textMessage) {
            return new TextMessage(textMessage.getPayload(), textMessage.isLast());
        }
        if (message instanceof BinaryMessage binaryMessage) {
            return new BinaryMessage(binaryMessage.getPayload().asReadOnlyBuffer(), binaryMessage.isLast());
        }
        if (message instanceof PingMessage pingMessage) {
            return new PingMessage(pingMessage.getPayload().asReadOnlyBuffer());
        }
        if (message instanceof PongMessage pongMessage) {
            return new PongMessage(pongMessage.getPayload().asReadOnlyBuffer());
        }
        return message;
    }

    /**
     * 上游 WebSocket 回调处理器。
     */
    private final class UpstreamHandler implements WebSocketHandler {

        /** 当前桥接状态。 */
        private final Bridge bridge;

        /**
         * 创建上游回调处理器。
         *
         * @param bridge 当前桥接状态
         */
        private UpstreamHandler(Bridge bridge) {
            this.bridge = bridge;
        }

        /**
         * 保存 AIO 上游 WebSocket 会话。
         *
         * @param upstream AIO 上游 WebSocket 会话
         */
        @Override
        public void afterConnectionEstablished(WebSocketSession upstream) {
            bridge.upstream.set(upstream);
        }

        /**
         * 将 AIO 上游消息转发到浏览器。
         *
         * @param upstream AIO 上游 WebSocket 会话
         * @param message 上游消息
         * @throws Exception 当转发失败时抛出
         */
        @Override
        public void handleMessage(WebSocketSession upstream, WebSocketMessage<?> message) throws Exception {
            bridge.sendToDownstream(copyMessage(message));
        }

        /**
         * 上游传输失败时关闭两侧连接。
         *
         * @param upstream AIO 上游 WebSocket 会话
         * @param exception 传输异常
         */
        @Override
        public void handleTransportError(WebSocketSession upstream, Throwable exception) {
            log.warn("沙箱视图 WebSocket 上游传输失败: downstream={}",
                    bridge.downstream.getId(), exception);
            bridge.closeBoth(CloseStatus.SERVER_ERROR);
        }

        /**
         * 上游关闭时同步关闭浏览器侧连接。
         *
         * @param upstream AIO 上游 WebSocket 会话
         * @param closeStatus 关闭状态
         */
        @Override
        public void afterConnectionClosed(WebSocketSession upstream, CloseStatus closeStatus) {
            bridge.closeBoth(closeStatus);
        }

        /**
         * 上游处理器不支持部分消息。
         *
         * @return false
         */
        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }

    /**
     * 维护一次浏览器会话与 AIO 上游会话的桥接状态。
     */
    private static final class Bridge {

        /** 浏览器侧会话。 */
        private final WebSocketSession downstream;

        /** AIO 上游会话。 */
        private final AtomicReference<WebSocketSession> upstream = new AtomicReference<>();

        /**
         * 创建桥接状态。
         *
         * @param downstream 浏览器侧会话
         */
        private Bridge(WebSocketSession downstream) {
            this.downstream = downstream;
        }

        /**
         * 将消息发给上游。
         *
         * @param message 待发送消息
         * @throws Exception 当发送失败时抛出
         */
        private void sendToUpstream(WebSocketMessage<?> message) throws Exception {
            sendIfOpen(upstream.get(), message);
        }

        /**
         * 将消息发给浏览器。
         *
         * @param message 待发送消息
         * @throws Exception 当发送失败时抛出
         */
        private void sendToDownstream(WebSocketMessage<?> message) throws Exception {
            sendIfOpen(downstream, message);
        }

        /**
         * 同步关闭两侧连接。
         *
         * @param status 关闭状态
         */
        private void closeBoth(CloseStatus status) {
            closeIfOpen(upstream.get(), status);
            closeIfOpen(downstream, status);
        }

        /**
         * 向仍打开的会话发送消息。
         *
         * @param session 目标会话
         * @param message 待发送消息
         * @throws Exception 当发送失败时抛出
         */
        private void sendIfOpen(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (session == null || !session.isOpen()) {
                return;
            }
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        }

        /**
         * 关闭仍打开的会话；关闭失败只记录在 WebSocket 上层日志中，不再递归抛出。
         *
         * @param session 目标会话
         * @param status 关闭状态
         */
        private void closeIfOpen(WebSocketSession session, CloseStatus status) {
            if (session == null || !session.isOpen()) {
                return;
            }
            try {
                session.close(status);
            } catch (Exception ignored) {
                // 关闭阶段无法恢复，调用方随后会清理桥接状态。
            }
        }
    }
}
