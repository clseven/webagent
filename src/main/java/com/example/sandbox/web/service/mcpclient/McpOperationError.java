package com.example.sandbox.web.service.mcpclient;

/**
 * 可安全展示给 Agent 或前端的 MCP 操作错误。
 *
 * @param code    稳定错误码
 * @param message 面向用户的中文说明
 * @param detail  经过裁剪的底层错误信息；没有额外信息时可为空
 */
public record McpOperationError(
        McpErrorCode code,
        String message,
        String detail
) {
}
