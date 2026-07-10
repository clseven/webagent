package com.example.sandbox.web.service.mcpclient;

import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpToolNameCodec;
import com.example.sandbox.web.service.tool.ImageBuffer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP 工具提供器。
 *
 * <p>直接对接 {@link McpClientManager}，把所有已配置 server
 * 的工具列表统一暴露给 Agent。</p>
 *
 * <p>系统级 MCP 工具向所有用户开放；用户级 MCP 工具通过 sessionId 解析 userId，
 * 只暴露当前用户已成功连接的 Server。</p>
 */
@Service
public class McpClientToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpClientToolProvider.class);

    /** 单会话内最多暴露的 MCP 工具数量，避免挤占模型上下文。 */
    private static final int MAX_TOOLS = 50;

    /** MCP Client 生命周期管理器。 */
    private final McpClientManager manager;

    /** MCP 配置服务，用于从 sessionId 稳定解析用户身份。 */
    private final McpConfigurationService configurationService;

    /** 图片缓冲区，MCP 工具返回的图片交由视觉 Hook 处理。 */
    private final ImageBuffer imageBuffer;

    /**
     * 创建 MCP 工具提供器。
     *
     * @param manager              MCP Client 生命周期管理器
     * @param configurationService MCP 配置服务
     * @param imageBuffer          图片缓冲区
     */
    public McpClientToolProvider(McpClientManager manager,
                                 McpConfigurationService configurationService,
                                 ImageBuffer imageBuffer) {
        this.manager = manager;
        this.configurationService = configurationService;
        this.imageBuffer = imageBuffer;
    }

    /**
     * 获取当前可用的所有 MCP 工具。
     *
     * @param sessionId 当前会话 ID
     * @return MCP 工具列表
     */
    public List<Tool> getTools(String sessionId) {
        if (!manager.isEnabled()) {
            return List.of();
        }

        Long userId;
        try {
            configurationService.ensureUserServersLoaded(sessionId);
            userId = configurationService.resolveUserId(sessionId);
        } catch (Exception e) {
            log.warn("解析 MCP 工具所属用户失败: session={}, 原因={}",
                    sessionId, e.getMessage());
            return List.of();
        }

        List<McpClientKey> keys = manager.listAvailableKeys(userId);
        Set<String> exposedNames = new HashSet<>();
        List<Tool> result = new ArrayList<>();
        for (McpClientKey key : keys) {
            for (McpSchema.Tool tool : manager.listTools(key)) {
                if (result.size() >= MAX_TOOLS) {
                    log.debug("MCP 工具数量达到上限 {}，截断剩余工具", MAX_TOOLS);
                    return List.copyOf(result);
                }
                String exposedName = uniqueName(key.serverId(), tool.name(), exposedNames);
                result.add(new RealMcpTool(
                        exposedName, key, tool, manager, configurationService, imageBuffer));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 兼容现有工作区刷新入口，并显式重新加载用户 MCP 配置。
     *
     * @param sessionId 会话 ID
     */
    public void evict(String sessionId) {
        configurationService.reloadUserServers(sessionId);
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
