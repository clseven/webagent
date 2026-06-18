package com.example.sandbox.web.service.mcpclient;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcp.McpToolNameCodec;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * 真 MCP 工具适配器：把单个 MCP {@link McpSchema.Tool} 适配成项目内部的 {@link Tool}。
 *
 * <p>每个实例对应"某个 server 的某个工具"。模型看到的是 {@link McpToolNameCodec} 编码后的安全名，
 * 执行时通过 {@link McpClientManager} 转发到真实 MCP server。</p>
 */
public class RealMcpTool implements Tool {

    /** 模型可见的安全工具名。 */
    private final String exposedName;

    /** MCP server 标识。 */
    private final String serverId;

    /** MCP server 内的原始工具名，用于去重比较和实际调用。 */
    private final String originalName;

    /** MCP 工具元数据。 */
    private final McpSchema.Tool tool;

    /** 客户端管理器，调用工具时转发到此处。 */
    private final McpClientManager manager;

    public RealMcpTool(String exposedName, String serverId, McpSchema.Tool tool,
                       McpClientManager manager) {
        this.exposedName = exposedName;
        this.serverId = serverId;
        this.originalName = tool.name();
        this.tool = tool;
        this.manager = manager;
    }

    /**
     * 返回 MCP server 内的原始工具名，用于与自定义工具名做去重比较。
     */
    public String getOriginalName() {
        return originalName;
    }

    /**
     * 返回 MCP server 标识。
     */
    public String getServerId() {
        return serverId;
    }

    @Override
    public ToolDefinition getDefinition() {
        String description = tool.description();
        if (description == null || description.isBlank()) {
            description = tool.title();
        }
        if (description == null || description.isBlank()) {
            description = "调用 MCP Server `" + serverId + "` 的 `" + originalName + "` 工具。";
        } else {
            description = "来自 MCP Server `" + serverId + "`：" + description;
        }

        Map<String, Object> schema = tool.inputSchema();
        if (schema == null || schema.isEmpty()) {
            schema = Map.of("type", "object", "properties", Map.of());
        }

        // sandboxType 不再绑定 AIO，标为 ALL：MCP 工具通常与沙箱无关，应在所有沙箱类型下可用
        return new ToolDefinition(exposedName, description, schema, "ALL");
    }

    /**
     * 执行 MCP 工具调用。
     *
     * <p>参考旧 McpDynamicTool 的策略：失败不在本层重试。MCP 工具可能产生副作用，
     * 由模型或上层根据语义决定是否重试。</p>
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult result = manager.callTool(serverId, originalName, arguments);
            return CallToolResultFormatter.format(result);
        } catch (Exception e) {
            return "ERROR: MCP 工具执行出错：" + e.getMessage();
        }
    }
}
