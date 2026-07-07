package com.example.sandbox.web.config;

import com.example.sandbox.web.controller.SandboxViewWebSocketProxyHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 沙箱视图 WebSocket 代理配置。
 *
 * <p>仅拦截 `/sandbox-view/{token}/...` 下的 WebSocket Upgrade 请求，普通 HTTP 请求仍由
 * `SandboxViewProxyController` 处理。</p>
 */
@Configuration
@EnableWebSocket
public class SandboxViewWebSocketConfig implements WebSocketConfigurer {

    /** 浏览器侧 WebSocket 文本缓冲区大小（64 KB）。 */
    private static final int TEXT_BUFFER_SIZE = 64 * 1024;

    /** 浏览器侧 WebSocket 二进制缓冲区大小（256 KB）。 */
    private static final int BINARY_BUFFER_SIZE = 256 * 1024;

    /** 沙箱视图 WebSocket 代理处理器。 */
    private final SandboxViewWebSocketProxyHandler proxyHandler;

    /**
     * 创建沙箱视图 WebSocket 配置。
     *
     * @param proxyHandler 沙箱视图 WebSocket 代理处理器
     */
    public SandboxViewWebSocketConfig(SandboxViewWebSocketProxyHandler proxyHandler) {
        this.proxyHandler = proxyHandler;
    }

    /**
     * 配置浏览器侧 WebSocket 缓冲区。
     *
     * <p>Spring Boot 默认 text buffer 仅 8 KB，code-server Management 通道单条消息可达 9 KB+，
     * 导致 1009（Message Too Big）断连。这里提高到 64 KB / 256 KB。</p>
     */
    @Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(TEXT_BUFFER_SIZE);
        container.setMaxBinaryMessageBufferSize(BINARY_BUFFER_SIZE);
        return container;
    }

    /**
     * 注册沙箱视图 WebSocket 代理路径。
     *
     * @param registry WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(proxyHandler, "/sandbox-view/{token}/**")
                .setAllowedOriginPatterns("*");
    }
}
