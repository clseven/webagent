package com.example.sandbox.web.service.mcpclient;

import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcp.McpToolNameCodec;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 真 MCP 工具提供器。
 *
 * <p>替换旧的 {@code McpToolProvider}（那一版调的是 AIO 沙箱代理的 REST，
 * 不是真 MCP 协议）。本类直接对接 {@link McpClientManager}，把所有已配置 server
 * 的工具列表统一暴露给 Agent。</p>
 *
 * <p>方法签名保留 {@code sessionId} 参数只是为了兼容现有 Agent 注入点。
 * MCP server 是全局共享的，所有会话看到的工具集合相同。</p>
 */
@Service
public class McpClientToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpClientToolProvider.class);

    /** 单会话内最多暴露的 MCP 工具数量，避免挤占模型上下文。 */
    private static final int MAX_TOOLS = 50;

    private final McpClientManager manager;

    public McpClientToolProvider(McpClientManager manager) {
        this.manager = manager;
    }

    /**
     * 获取当前可用的所有 MCP 工具。
     *
     * @param sessionId 会话 ID（保留参数兼容旧签名，实际不使用）
     * @return MCP 工具列表
     */
    public List<Tool> getTools(String sessionId) {
        List<String> serverIds = manager.listServerIds();
        if (serverIds.isEmpty()) {
            return List.of();
        }

        Set<String> exposedNames = new HashSet<>();
        List<Tool> result = new ArrayList<>();
        for (String serverId : serverIds) {
            for (McpSchema.Tool tool : manager.listTools(serverId)) {
                if (result.size() >= MAX_TOOLS) {
                    log.debug("MCP 工具数量达到上限 {}，截断剩余工具", MAX_TOOLS);
                    return List.copyOf(result);
                }
                String exposedName = uniqueName(serverId, tool.name(), exposedNames);
                result.add(new RealMcpTool(exposedName, serverId, tool, manager));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 兼容旧 API 的清缓存方法。新实现是无状态的，这里是 no-op。
     *
     * @param sessionId 会话 ID
     */
    public void evict(String sessionId) {
        // no-op: tool list 由 McpClientManager 通过 tools/list_changed 通知自动维护
    }

    /**
     * 生成不与已暴露工具同名的安全工具名。
     */
    private String uniqueName(String serverId, String toolName, Set<String> exposedNames) {
        String candidate = McpToolNameCodec.toToolName(serverId, toolName);
        int index = 1;
        while (exposedNames.contains(candidate)) {
            candidate = McpToolNameCodec.toCollisionName(serverId, toolName, index);
            index++;
        }
        exposedNames.add(candidate);
        return candidate;
    }
}
