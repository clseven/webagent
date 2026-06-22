package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpConfigurationService;
import com.example.sandbox.web.service.mcpclient.McpManagementResultFormatter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP 配置重新加载工具。
 *
 * <p>当用户或 Agent 已修改 {@code /home/gem/.mcp/servers.json} 时，
 * 本工具显式触发后端读取配置并更新官方 MCP Java SDK Client。</p>
 */
@Component
public class McpReloadTool implements Tool {

    /** 模型可见工具名。 */
    private static final String NAME = "mcp_reload";

    /** MCP 配置编排服务。 */
    private final McpConfigurationService configurationService;

    /**
     * 创建 MCP 配置重新加载工具。
     *
     * @param configurationService MCP 配置编排服务
     */
    public McpReloadTool(McpConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * 返回 MCP 重新加载工具定义。
     *
     * @return 无参数工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                NAME,
                "从用户沙箱的 /home/gem/.mcp/servers.json 重新加载私有 MCP。"
                        + "当配置文件被手动编辑后调用；新增工具从下一条用户消息开始可用。",
                Map.of("type", "object", "properties", Map.of()),
                "AIO"
        );
    }

    /**
     * 读取并应用当前用户 MCP 配置。
     *
     * @param sessionId 当前会话 ID
     * @param arguments 空参数对象
     * @return 重新加载摘要
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        return McpManagementResultFormatter.formatReload(
                configurationService.reloadUserServers(sessionId));
    }
}
