package com.example.sandbox.web.service.mcp;

/**
 * MCP 动态工具引用。
 *
 * <p>模型看到的是安全编码后的工具名，本记录保存该工具名对应的
 * MCP Server 和原始 MCP 工具名，执行时据此转发到 AIO MCP API。</p>
 *
 * @param server MCP Server 名称
 * @param tool   MCP Server 内的原始工具名
 */
public record McpToolRef(String server, String tool) {
}
