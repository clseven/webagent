package com.example.sandbox.web.service.mcpclient;

/**
 * 携带结构化 MCP 连接错误的运行时异常。
 *
 * <p>Manager 抛出该异常，配置服务保留其中的错误码和用户说明，
 * 同时通过 cause 保存原始 SDK 异常供后端日志诊断。</p>
 */
public class McpConnectionException extends IllegalStateException {

    /** 可展示的结构化错误。 */
    private final McpOperationError operationError;

    /**
     * 创建 MCP 连接异常。
     *
     * @param operationError 结构化错误
     * @param cause          原始 SDK 或网络异常
     */
    public McpConnectionException(McpOperationError operationError, Throwable cause) {
        super(operationError.message(), cause);
        this.operationError = operationError;
    }

    /**
     * 获取结构化错误。
     *
     * @return MCP 操作错误
     */
    public McpOperationError getOperationError() {
        return operationError;
    }
}
