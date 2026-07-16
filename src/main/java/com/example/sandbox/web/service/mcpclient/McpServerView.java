package com.example.sandbox.web.service.mcpclient;

import java.util.List;

/**
 * 可安全展示给 Agent 或前端的 MCP Server 状态。
 *
 * <p>该视图故意不包含 headers、环境变量或其他敏感配置。</p>
 *
 * @param scope      配置作用域
 * @param id         Server ID
 * @param type       transport 类型
 * @param url        远程服务地址或 shell 命令摘要；不会包含 headers 或 env
 * @param enabled    配置是否启用
 * @param connected  当前是否存在可用 Client
 * @param toolNames  当前缓存的工具名称
 * @param lastError  最近一次结构化连接错误；没有错误时为 null
 * @param headerNames 已配置的请求头名称；不包含请求头值
 * @param requestTimeoutSeconds 单 Server 请求超时秒数；为空时继承全局值
 */
public record McpServerView(
        McpClientScope scope,
        String id,
        String type,
        String url,
        boolean enabled,
        boolean connected,
        List<String> toolNames,
        McpOperationError lastError,
        List<String> headerNames,
        Integer requestTimeoutSeconds
) {
}
