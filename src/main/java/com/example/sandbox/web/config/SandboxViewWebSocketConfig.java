package com.example.sandbox.web.config;

import com.example.sandbox.web.controller.SandboxViewWebSocketProxyHandler;
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
