package com.example.sandbox.web.service.mcpclient;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端配置。
 *
 * <p>对应 application.yml 中的 {@code agent.mcp.*}，例如：</p>
 *
 * <pre>{@code
 * agent:
 *   mcp:
 *     enabled: true
 *     request-timeout: 60s
 *     servers:
 *       - id: filesystem
 *         type: stdio
 *         command: npx
 *         args: ["-y", "@modelcontextprotocol/server-filesystem", "D:/workspace"]
 *       - id: github
 *         type: streamable-http
 *         url: https://example.com/mcp
 *         headers:
 *           Authorization: "Bearer xxx"
 * }</pre>
 */
@ConfigurationProperties(prefix = "agent.mcp")
public class McpClientProperties {

    /** 是否启用真 MCP 客户端，默认关闭。 */
    private boolean enabled = false;

    /** 单次 MCP 请求超时，默认 60 秒。 */
    private Duration requestTimeout = Duration.ofSeconds(60);

    /** 单次 initialize 握手超时，默认 30 秒。 */
    private Duration initializationTimeout = Duration.ofSeconds(30);

    /** 已配置的 MCP Server 列表。 */
    private List<McpServerConfig> servers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public List<McpServerConfig> getServers() {
        return servers;
    }

    public void setServers(List<McpServerConfig> servers) {
        this.servers = servers != null ? servers : new ArrayList<>();
    }
}
