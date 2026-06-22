package com.example.sandbox.web.service.mcpclient;

/**
 * 已解析的 Streamable HTTP MCP 地址。
 *
 * @param baseUri       只包含协议、主机和非默认端口的基础地址
 * @param endpoint      精确保留路径和查询参数的 MCP endpoint
 * @param normalizedUrl 用于持久化和重复检测的规范化完整 URL
 */
public record McpResolvedEndpoint(
        String baseUri,
        String endpoint,
        String normalizedUrl
) {
}
