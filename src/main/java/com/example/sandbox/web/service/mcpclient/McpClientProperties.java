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

    /** 是否启用真 MCP 客户端，当前项目默认开启。 */
    private boolean enabled = true;

    /** 单次 MCP 请求超时，默认 60 秒。 */
    private Duration requestTimeout = Duration.ofSeconds(60);

    /** 单次 initialize 握手超时，默认 30 秒。 */
    private Duration initializationTimeout = Duration.ofSeconds(30);

    /** 已配置的 MCP Server 列表。 */
    private List<McpServerConfig> servers = new ArrayList<>();

    /** 单个用户最多允许配置的 MCP Server 数量。 */
    private int maxUserServers = 10;

    /** 是否允许用户 MCP 使用明文 HTTP，默认只允许 HTTPS。 */
    private boolean userAllowHttp = false;

    /** 是否允许用户 MCP 连接环回、私网和链路本地地址，默认禁止。 */
    private boolean userAllowPrivateNetwork = false;

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

    /**
     * 获取单个用户最多允许配置的 Server 数量。
     *
     * @return Server 数量上限
     */
    public int getMaxUserServers() {
        return maxUserServers;
    }

    /**
     * 设置单个用户最多允许配置的 Server 数量。
     *
     * @param maxUserServers Server 数量上限
     */
    public void setMaxUserServers(int maxUserServers) {
        this.maxUserServers = maxUserServers;
    }

    /**
     * 判断是否允许用户配置明文 HTTP MCP。
     *
     * @return true 表示允许 HTTP，false 表示仅允许 HTTPS
     */
    public boolean isUserAllowHttp() {
        return userAllowHttp;
    }

    /**
     * 设置是否允许用户配置明文 HTTP MCP。
     *
     * @param userAllowHttp 是否允许 HTTP
     */
    public void setUserAllowHttp(boolean userAllowHttp) {
        this.userAllowHttp = userAllowHttp;
    }

    /**
     * 判断是否允许用户 MCP 连接私网地址。
     *
     * @return true 表示允许私网地址
     */
    public boolean isUserAllowPrivateNetwork() {
        return userAllowPrivateNetwork;
    }

    /**
     * 设置是否允许用户 MCP 连接私网地址。
     *
     * @param userAllowPrivateNetwork 是否允许私网地址
     */
    public void setUserAllowPrivateNetwork(boolean userAllowPrivateNetwork) {
        this.userAllowPrivateNetwork = userAllowPrivateNetwork;
    }
}
