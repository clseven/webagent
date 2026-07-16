package com.example.sandbox.web.model.request;

import java.util.Map;

/**
 * 用户私有 Streamable HTTP MCP Server 的保存请求。
 *
 * <p>更新已有配置时，请求头值允许为空；后端会保留同名旧值。删除某个请求头时，
 * 客户端应直接从 headers 映射中移除该名称。</p>
 *
 * @param id                    新建时使用的 Server ID；更新时由路径参数决定
 * @param url                   完整 Streamable HTTP endpoint
 * @param headers               自定义请求头；更新时空值表示保留同名旧值
 * @param enabled               是否启用，空值按启用处理
 * @param requestTimeoutSeconds 单次 MCP 工具调用超时秒数；空值继承系统默认值
 */
public record McpServerRequest(
        String id,
        String url,
        Map<String, String> headers,
        Boolean enabled,
        Integer requestTimeoutSeconds
) {
}
