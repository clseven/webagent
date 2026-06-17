package com.example.sandbox.web.service.mcp;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.mcp.model.McpToolDescriptor;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MCP 动态工具提供器。
 *
 * <p>该服务从当前会话的 AIO Sandbox 发现 MCP Server 和工具，
 * 并把它们适配成项目内部的 {@link Tool}，供 Agent 暴露给 LLM。</p>
 */
@Service
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    /** MCP 工具发现缓存时间。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** 每个会话最多暴露的 MCP 工具数量，避免工具过多挤占模型上下文。 */
    private static final int MAX_TOOLS_PER_SESSION = 30;

    /** 沙箱客户端工厂，用于获取当前会话的 AIO 客户端。 */
    @Autowired
    private SandboxClientFactory factory;

    /** 会话级 MCP 工具缓存。 */
    private final ConcurrentMap<String, CachedTools> cache = new ConcurrentHashMap<>();

    /**
     * 获取当前会话可用的 MCP 动态工具。
     *
     * <p>发现失败时返回空列表，不阻断本地工具和对话流程。这里不重试，
     * 因为失败通常来自 MCP Server 未配置、进程未启动或外部依赖不可用，
     * 重试无法在当前请求内稳定修复。</p>
     *
     * @param sessionId 会话 ID
     * @return 当前会话可用的 MCP 动态工具
     */
    public List<Tool> getTools(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        CachedTools cached = cache.get(sessionId);
        if (cached != null && !cached.expired()) {
            return cached.tools();
        }

        try {
            List<Tool> tools = discoverTools(sessionId);
            cache.put(sessionId, new CachedTools(Instant.now().plus(CACHE_TTL), tools));
            return tools;
        } catch (Exception e) {
            log.debug("MCP 动态工具发现失败，会话: {}, 原因: {}", sessionId, e.getMessage());
            cache.put(sessionId, new CachedTools(Instant.now().plus(CACHE_TTL), List.of()));
            return List.of();
        }
    }

    /**
     * 清理某个会话的 MCP 工具缓存。
     *
     * @param sessionId 会话 ID
     */
    public void evict(String sessionId) {
        if (sessionId != null) {
            cache.remove(sessionId);
        }
    }

    /**
     * 从 AIO Sandbox 发现 MCP 工具。
     *
     * @param sessionId 会话 ID
     * @return 动态工具列表
     */
    private List<Tool> discoverTools(String sessionId) {
        AioClient client = factory.getAioClient(sessionId);
        List<String> servers = client.mcp().listServers();
        if (servers.isEmpty()) {
            return List.of();
        }

        Set<String> exposedNames = new HashSet<>();
        List<Tool> dynamicTools = new ArrayList<>();
        for (String server : servers) {
            try {
                appendServerTools(client, server, exposedNames, dynamicTools);
            } catch (Exception e) {
                log.debug("跳过不可用的 MCP Server: server={}, 原因={}", server, e.getMessage());
            }
            if (dynamicTools.size() >= MAX_TOOLS_PER_SESSION) {
                break;
            }
        }

        log.info("MCP 动态工具发现完成，会话: {}, servers={}, tools={}",
                sessionId, servers, dynamicTools.size());
        return List.copyOf(dynamicTools);
    }

    /**
     * 追加某个 MCP Server 的工具。
     *
     * @param client       当前 AIO 客户端
     * @param server       MCP Server 名称
     * @param exposedNames 已暴露工具名集合
     * @param dynamicTools 动态工具输出列表
     */
    private void appendServerTools(AioClient client, String server, Set<String> exposedNames,
                                   List<Tool> dynamicTools) {
        List<McpToolDescriptor> descriptors = client.mcp().listTools(server);
        for (McpToolDescriptor descriptor : descriptors) {
            if (dynamicTools.size() >= MAX_TOOLS_PER_SESSION) {
                return;
            }
            String exposedName = uniqueName(server, descriptor.name(), exposedNames);
            McpToolRef ref = new McpToolRef(server, descriptor.name());
            dynamicTools.add(new McpDynamicTool(exposedName, ref, descriptor, factory));
        }
    }

    /**
     * 生成不与已有工具冲突的模型可见名称。
     *
     * @param server       MCP Server 名称
     * @param toolName     MCP 原始工具名
     * @param exposedNames 已暴露工具名集合
     * @return 唯一工具名
     */
    private String uniqueName(String server, String toolName, Set<String> exposedNames) {
        String candidate = McpToolNameCodec.toToolName(server, toolName);
        int index = 1;
        while (exposedNames.contains(candidate)) {
            candidate = McpToolNameCodec.toCollisionName(server, toolName, index);
            index++;
        }
        exposedNames.add(candidate);
        return candidate;
    }

    /**
     * 会话级 MCP 工具缓存。
     *
     * @param expiresAt 缓存过期时间
     * @param tools     已发现的动态工具
     */
    private record CachedTools(Instant expiresAt, List<Tool> tools) {

        /**
         * 判断缓存是否过期。
         *
         * @return true 表示已过期
         */
        private boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
