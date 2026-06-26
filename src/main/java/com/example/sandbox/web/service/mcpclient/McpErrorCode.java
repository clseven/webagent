package com.example.sandbox.web.service.mcpclient;

/**
 * MCP 配置、连接和工具发现错误码。
 *
 * <p>错误码用于让 Agent 区分后端开关、URL、网络、认证和协议问题，
 * 避免把所有 initialize 失败都解释成“MCP 未启用”。</p>
 */
public enum McpErrorCode {
    /** WebAgent 后端 MCP 总开关关闭。 */
    MCP_DISABLED,
    /** 用户配置文件或 Server 配置无效。 */
    CONFIG_INVALID,
    /** MCP 域名无法解析。 */
    DNS_FAILED,
    /** 建立网络连接超时。 */
    CONNECT_TIMEOUT,
    /** 目标服务拒绝连接。 */
    CONNECT_REFUSED,
    /** HTTPS 或 TLS 握手失败。 */
    TLS_FAILED,
    /** 服务要求认证或当前凭据无权限。 */
    AUTH_REQUIRED,
    /** endpoint 不存在或不接受 Streamable HTTP 请求。 */
    ENDPOINT_NOT_FOUND,
    /** 服务返回其他 HTTP 4xx。 */
    HTTP_CLIENT_ERROR,
    /** 服务返回 HTTP 5xx。 */
    HTTP_SERVER_ERROR,
    /** initialize 握手超过配置时限。 */
    INITIALIZE_TIMEOUT,
    /** 目标响应不符合 MCP initialize 协议。 */
    PROTOCOL_ERROR,
    /** initialize 成功后获取工具列表失败。 */
    TOOLS_LIST_FAILED,
    /** 沙箱内 Shell 命令执行失败。 */
    SHELL_EXEC_FAILED,
    /** 沙箱内 supergateway 启动失败或未按时监听端口。 */
    SUPERGATEWAY_START_FAILED,
    /** MCP SSE 或 JSON-RPC 响应解析失败。 */
    SSE_PARSE_ERROR,
    /** 沙箱内端口分配失败。 */
    PORT_ALLOCATION_FAILED,
    /** 无法进一步分类的 MCP 错误。 */
    UNKNOWN
}
