package com.example.sandbox.web.service.mcpclient;

/**
 * MCP 连接失败发生的阶段。
 */
public enum McpConnectionStage {
    /** initialize 协议握手阶段。 */
    INITIALIZE,
    /** initialize 后首次读取工具列表阶段。 */
    LIST_TOOLS,
    /** 已连接 Client 的工具列表刷新阶段。 */
    REFRESH_TOOLS
}
