package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpConfigurationService;
import com.example.sandbox.web.service.mcpclient.McpManagementResultFormatter;
import com.example.sandbox.web.service.mcpclient.McpServerConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户远程 MCP 添加或更新工具。
 *
 * <p>第一版只接受无需 headers 的 Streamable HTTP MCP。模型必须先向用户展示来源、
 * URL 和预期能力，并取得明确确认后才能把 {@code confirmed} 设为 true。</p>
 */
@Component
public class McpAddOrUpdateServerTool implements Tool {

    /** 模型可见工具名。 */
    private static final String NAME = "mcp_add_or_update_server";

    /** MCP 配置编排服务。 */
    private final McpConfigurationService configurationService;

    /**
     * 创建用户远程 MCP 添加或更新工具。
     *
     * @param configurationService MCP 配置编排服务
     */
    public McpAddOrUpdateServerTool(McpConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * 返回用户远程 MCP 添加或更新工具定义。
     *
     * @return 带 Server ID、URL、启用状态和确认标记的工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server_id", Map.of(
                "type", "string",
                "description", "小写 Server ID；连接失败后重试必须复用原 ID，不能另建重复配置"
        ));
        properties.put("url", Map.of(
                "type", "string",
                "description", "官方配置给出的精确 Streamable HTTP endpoint；不能填写官网或猜测 /mcp"
        ));
        properties.put("enabled", Map.of(
                "type", "boolean",
                "description", "是否启用，默认 true"
        ));
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "只有在用户已明确确认该 MCP 来源、URL 和能力后才能设为 true"
        ));

        return new ToolDefinition(
                NAME,
                "把远程 MCP Server 添加到当前 WebAgent。若本轮尚未核实配置，必须先搜索官方来源，"
                        + "向用户展示 URL、能力和限制并获得确认；若对话历史已经完成核实，且用户当前"
                        + "回复“确认”“可以”“安装吧”等肯定表达，应直接使用历史中的配置调用本工具，"
                        + "不要再询问 VS Code、Claude Desktop 等目标环境。URL 必须是精确 endpoint，"
                        + "重试时复用原 server_id；相同 endpoint 会自动复用已有配置。"
                        + "暂不支持 stdio、headers 或 Token。",
                Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("server_id", "url", "confirmed")
                ),
                "AIO"
        );
    }

    /**
     * 保存配置并立即尝试连接远程 MCP。
     *
     * @param sessionId 当前会话 ID
     * @param arguments Server ID、URL、启用状态和确认标记
     * @return 配置写入与连接结果
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        if (!Boolean.TRUE.equals(arguments.get("confirmed"))) {
            return "错误：安装 MCP 前必须先向用户展示来源、URL 和能力，并获得明确确认。";
        }

        String serverId = stringValue(arguments.get("server_id"));
        String url = stringValue(arguments.get("url"));
        if (serverId == null || url == null) {
            return "错误：server_id 和 url 不能为空。";
        }

        McpServerConfig config = new McpServerConfig();
        config.setId(serverId);
        config.setType("streamable-http");
        config.setUrl(url);
        config.setEnabled(!Boolean.FALSE.equals(arguments.get("enabled")));
        return McpManagementResultFormatter.formatReload(
                configurationService.addOrReplaceUserServer(sessionId, config));
    }

    /**
     * 将参数转换为去除首尾空白的非空字符串。
     *
     * @param value 原始参数
     * @return 非空文本；参数为空时返回 null
     */
    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
