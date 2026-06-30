package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpConfigurationService;
import com.example.sandbox.web.service.mcpclient.McpManagementResultFormatter;
import com.example.sandbox.web.service.mcpclient.McpServerConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户私有 MCP 添加或更新工具。
 *
 * <p>支持无需 headers 的远程 Streamable HTTP MCP，以及在用户 Sandbox 内运行的
 * shell transport。模型必须先向用户展示来源、连接方式和预期能力，并取得明确确认后
 * 才能把 {@code confirmed} 设为 true。</p>
 */
@Component
public class McpAddOrUpdateServerTool implements Tool {

    /** 模型可见工具名。 */
    private static final String NAME = "mcp_add_or_update_server";

    /** MCP 配置编排服务。 */
    private final McpConfigurationService configurationService;

    /**
     * 创建用户私有 MCP 添加或更新工具。
     *
     * @param configurationService MCP 配置编排服务
     */
    public McpAddOrUpdateServerTool(McpConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * 返回用户私有 MCP 添加或更新工具定义。
     *
     * @return 带 Server ID、transport 参数、启用状态和确认标记的工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server_id", Map.of(
                "type", "string",
                "description", "小写 Server ID；重试时复用，不另建"
        ));
        properties.put("type", Map.of(
                "type", "string",
                "enum", List.of("streamable-http", "shell"),
                "description", "streamable-http | shell；省略时按 streamable-http 处理"
        ));
        properties.put("url", Map.of(
                "type", "string",
                "description", "streamable-http 的精确 endpoint；shell 类型不要填写"
        ));
        properties.put("command", Map.of(
                "type", "string",
                "description", "shell 类型的 stdio 启动命令，例如 npx、python、node"
        ));
        properties.put("args", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "shell 类型的命令参数列表，需完整填写不留空"
        ));
        properties.put("enabled", Map.of(
                "type", "boolean",
                "description", "是否启用，默认 true"
        ));
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "用户已确认安装方案后设为 true"
        ));

        return new ToolDefinition(
                NAME,
                "添加或更新当前用户的私有 MCP Server。支持 streamable-http（远程 endpoint）"
                        + "和 shell（在用户沙箱内 stdio 启动）。需要 confirmed=true 才会执行。",
                Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("server_id", "confirmed")
                ),
                "AIO"
        );
    }

    /**
     * 保存配置并立即尝试连接 MCP。
     *
     * @param sessionId 当前会话 ID
     * @param arguments Server ID、transport 参数、启用状态和确认标记
     * @return 配置写入与连接结果
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        if (!Boolean.TRUE.equals(arguments.get("confirmed"))) {
            return "错误：安装 MCP 前必须先向用户展示来源、URL 和能力，并获得明确确认。";
        }

        String serverId = stringValue(arguments.get("server_id"));
        if (serverId == null) {
            return "错误：server_id 不能为空。";
        }

        String type = stringValue(arguments.get("type"));
        if (type == null) {
            type = "streamable-http";
        }
        McpServerConfig config = new McpServerConfig();
        config.setId(serverId);
        config.setEnabled(!Boolean.FALSE.equals(arguments.get("enabled")));
        if ("shell".equalsIgnoreCase(type)) {
            String command = stringValue(arguments.get("command"));
            if (command == null) {
                return "错误：shell 类型必须提供 command。";
            }
            config.setType("shell");
            config.setCommand(command);
            List<String> args;
            try {
                args = stringList(arguments.get("args"));
            } catch (IllegalArgumentException e) {
                return "错误：" + e.getMessage();
            }
            String validationError = validateShellArgs(serverId, command, args);
            if (validationError != null) {
                return validationError;
            }
            config.setArgs(args);
        } else if ("streamable-http".equalsIgnoreCase(type) || "http".equalsIgnoreCase(type)) {
            String url = stringValue(arguments.get("url"));
            if (url == null) {
                return "错误：streamable-http 类型必须提供 url。";
            }
            config.setType("streamable-http");
            config.setUrl(url);
        } else {
            return "错误：type 只能是 streamable-http 或 shell。";
        }
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

    /**
     * 将参数转换为字符串列表。
     *
     * @param value 原始参数
     * @return 去除首尾空白后的字符串列表
     * @throws IllegalArgumentException 参数列表包含空白项时抛出，避免保存不可启动的 shell 配置
     */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String item = stringValue(values.get(i));
            if (item == null) {
                throw new IllegalArgumentException("shell args 第 " + (i + 1)
                        + " 项不能为空。filesystem MCP 应使用 command=npx，args=[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/home/gem/workspace\"]。");
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    /**
     * 校验 shell MCP 参数中容易由模型遗漏的关键项。
     *
     * @param serverId Server ID
     * @param command  启动命令
     * @param args     启动参数
     * @return 错误文本；参数可用时返回 null
     */
    private String validateShellArgs(String serverId, String command, List<String> args) {
        boolean filesystemByNpx = "filesystem".equalsIgnoreCase(serverId)
                && "npx".equalsIgnoreCase(command);
        if (filesystemByNpx && !args.contains("@modelcontextprotocol/server-filesystem")) {
            return "错误：filesystem MCP 缺少 npm 包名 @modelcontextprotocol/server-filesystem。"
                    + "请使用 command=npx，args=[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/home/gem/workspace\"]。";
        }
        return null;
    }
}
