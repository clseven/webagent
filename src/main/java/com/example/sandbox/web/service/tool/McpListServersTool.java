package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpConfigurationService;
import com.example.sandbox.web.service.mcpclient.McpManagementResultFormatter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP Server 状态查询工具。
 *
 * <p>列出系统内置和当前用户私有 MCP 的连接状态与工具名称，
 * 但不会返回 headers、Token、环境变量等敏感配置。</p>
 */
@Component
public class McpListServersTool implements Tool {

    /** 模型可见工具名。 */
    private static final String NAME = "mcp_list_servers";

    /** MCP 配置编排服务。 */
    private final McpConfigurationService configurationService;

    /**
     * 创建 MCP Server 状态查询工具。
     *
     * @param configurationService MCP 配置编排服务
     */
    public McpListServersTool(McpConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * 返回 MCP Server 状态查询工具定义。
     *
     * @return 无参数工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                NAME,
                "列出系统内置和当前用户私有 MCP Server，包括连接状态和可用工具；"
                        + "不会显示认证凭据。",
                Map.of("type", "object", "properties", Map.of()),
                "AIO"
        );
    }

    /**
     * 查询当前会话可见的 MCP Server。
     *
     * @param sessionId 当前会话 ID
     * @param arguments 空参数对象
     * @return Server 状态清单
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            return McpManagementResultFormatter.formatServers(
                    configurationService.listServers(sessionId));
        } catch (Exception e) {
            return "查询 MCP Server 失败：" + e.getMessage();
        }
    }
}
