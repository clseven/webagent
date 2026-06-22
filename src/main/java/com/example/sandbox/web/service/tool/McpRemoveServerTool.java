package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpConfigurationService;
import com.example.sandbox.web.service.mcpclient.McpManagementResultFormatter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户私有 MCP 删除工具。
 *
 * <p>删除操作会同时更新沙箱配置文件并关闭运行时 Client，必须获得用户明确确认。</p>
 */
@Component
public class McpRemoveServerTool implements Tool {

    /** 模型可见工具名。 */
    private static final String NAME = "mcp_remove_server";

    /** MCP 配置编排服务。 */
    private final McpConfigurationService configurationService;

    /**
     * 创建用户私有 MCP 删除工具。
     *
     * @param configurationService MCP 配置编排服务
     */
    public McpRemoveServerTool(McpConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * 返回用户私有 MCP 删除工具定义。
     *
     * @return 带 Server ID 和确认标记的工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server_id", Map.of(
                "type", "string",
                "description", "要删除的用户私有 MCP Server ID"
        ));
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "只有在用户明确确认删除后才能设为 true"
        ));

        return new ToolDefinition(
                NAME,
                "删除当前用户的私有 MCP Server。删除前必须获得用户明确确认；"
                        + "系统内置 MCP 不能通过该工具删除。",
                Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("server_id", "confirmed")
                ),
                "AIO"
        );
    }

    /**
     * 删除配置并关闭对应用户 MCP Client。
     *
     * @param sessionId 当前会话 ID
     * @param arguments Server ID 和确认标记
     * @return 删除与重新加载结果
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        if (!Boolean.TRUE.equals(arguments.get("confirmed"))) {
            return "错误：删除 MCP Server 前必须获得用户明确确认。";
        }
        Object value = arguments.get("server_id");
        String serverId = value != null ? value.toString().trim() : "";
        if (serverId.isEmpty()) {
            return "错误：server_id 不能为空。";
        }
        return McpManagementResultFormatter.formatReload(
                configurationService.removeUserServer(sessionId, serverId));
    }
}
