package com.example.sandbox.web.service.mcpclient;

/**
 * MCP Client 的配置作用域。
 *
 * <p>系统级 Client 来自应用配置并由所有用户共享；用户级 Client 来自用户沙箱配置，
 * 只能被所属用户的会话使用。</p>
 */
public enum McpClientScope {

    /** 系统管理员配置、所有用户共享的 MCP Client。 */
    SYSTEM,

    /** 用户私有配置、按用户隔离的 MCP Client。 */
    USER
}
