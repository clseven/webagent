package com.example.sandbox.web.service.mcpclient;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户沙箱中的 MCP 配置文档。
 *
 * <p>配置文件固定存放在 {@code /home/gem/.mcp/servers.json}，当前只支持版本 1。</p>
 */
public class McpUserConfigDocument {

    /** 配置结构版本，用于未来兼容升级。 */
    private int version = 1;

    /** 用户声明的 MCP Server 列表。 */
    private List<McpServerConfig> servers = new ArrayList<>();

    /**
     * 获取配置结构版本。
     *
     * @return 配置结构版本
     */
    public int getVersion() {
        return version;
    }

    /**
     * 设置配置结构版本。
     *
     * @param version 配置结构版本
     */
    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * 获取 MCP Server 列表。
     *
     * @return 非 null 的 Server 列表
     */
    public List<McpServerConfig> getServers() {
        return servers;
    }

    /**
     * 设置 MCP Server 列表。
     *
     * @param servers MCP Server 列表；null 会转换为空列表
     */
    public void setServers(List<McpServerConfig> servers) {
        this.servers = servers != null ? new ArrayList<>(servers) : new ArrayList<>();
    }
}
