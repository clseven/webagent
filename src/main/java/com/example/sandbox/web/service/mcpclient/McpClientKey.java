package com.example.sandbox.web.service.mcpclient;

/**
 * MCP Client 的唯一键。
 *
 * @param scope    Client 作用域
 * @param userId   用户级 Client 的所属用户 ID；系统级 Client 为 null
 * @param serverId MCP Server 逻辑标识
 */
public record McpClientKey(
        McpClientScope scope,
        Long userId,
        String serverId
) {

    /**
     * 创建系统级 Client 键。
     *
     * @param serverId MCP Server 逻辑标识
     * @return 系统级 Client 键
     */
    public static McpClientKey system(String serverId) {
        return new McpClientKey(McpClientScope.SYSTEM, null, serverId);
    }

    /**
     * 创建用户级 Client 键。
     *
     * @param userId   所属用户 ID
     * @param serverId MCP Server 逻辑标识
     * @return 用户级 Client 键
     */
    public static McpClientKey user(Long userId, String serverId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户级 MCP Client 必须提供 userId");
        }
        return new McpClientKey(McpClientScope.USER, userId, serverId);
    }

    /**
     * 返回适合日志输出且不包含凭据的标识。
     *
     * @return 作用域、用户和 Server 组成的安全标识
     */
    public String displayName() {
        if (scope == McpClientScope.SYSTEM) {
            return "SYSTEM/" + serverId;
        }
        return "USER/" + userId + "/" + serverId;
    }
}
