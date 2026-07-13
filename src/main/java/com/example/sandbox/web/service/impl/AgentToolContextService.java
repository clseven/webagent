package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.SubAgentConfigProperties;
import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpClientToolProvider;
import com.example.sandbox.web.service.mcpclient.RealMcpTool;
import com.example.sandbox.web.service.tool.KnowledgeSearchTool;
import com.example.sandbox.web.service.tool.RunSubagentTool;
import com.example.sandbox.web.service.tool.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 工具上下文服务。
 *
 * <p>集中处理工具过滤、MCP 动态工具合并、知识库工具动态描述和工具状态清理，
 * 避免同步/流式对话入口重复理解工具细节。</p>
 */
@Service
public class AgentToolContextService {

    private static final Logger log = LoggerFactory.getLogger(AgentToolContextService.class);

    /** 所有 Spring 注册的基础工具。 */
    private final List<Tool> tools;

    /** MCP 动态工具提供器。 */
    private final McpClientToolProvider mcpToolProvider;

    /** 沙箱服务，用于判断当前会话的沙箱类型。 */
    private final SandboxService sandboxService;

    /** 子代理配置，用于过滤 run_subagent 工具。 */
    private final SubAgentConfigProperties subAgentConfig;

    /** MCP 动态工具开关，默认关闭。 */
    @Value("${agent.mcp.enabled:false}")
    private boolean mcpEnabled;

    /**
     * 创建工具上下文服务。
     *
     * @param tools           Spring 注册的工具列表
     * @param mcpToolProvider MCP 动态工具提供器
     * @param sandboxService  沙箱服务
     * @param subAgentConfig  子代理配置
     */
    public AgentToolContextService(List<Tool> tools,
                                   McpClientToolProvider mcpToolProvider,
                                   SandboxService sandboxService,
                                   SubAgentConfigProperties subAgentConfig) {
        this.tools = tools;
        this.mcpToolProvider = mcpToolProvider;
        this.sandboxService = sandboxService;
        this.subAgentConfig = subAgentConfig;
    }

    /**
     * 构建当前会话可用工具上下文。
     *
     * @param sessionId     会话 ID
     * @param app           当前会话关联的 Agent 应用；可为 null
     * @param kbDescription 知识库描述；无关联知识库时为 null
     * @return 工具上下文
     */
    public AgentToolContext build(String sessionId, AgentAppEntity app, String kbDescription) {
        boolean isAio = sandboxService.isAioSandbox(sessionId);
        String targetType = isAio ? "AIO" : "COMMON";
        List<Tool> filteredTools = new ArrayList<>(tools.stream()
                .filter(t -> {
                    String type = t.getDefinition().getSandboxType();
                    return "ALL".equals(type) || targetType.equals(type);
                })
                .toList());

        filteredTools = mergeMcpTools(filteredTools, sessionId);
        filteredTools = filterWebSearchTool(filteredTools);
        filteredTools = filterSubAgentTool(filteredTools);
        filteredTools = filterKnowledgeSearchTool(filteredTools, app);

        KnowledgeSearchTool knowledgeSearchTool = configureKnowledgeSearchTool(filteredTools, app);
        List<ToolDefinition> toolDefinitions = buildToolDefinitions(filteredTools, app, kbDescription);
        log.info("【工具过滤】沙箱类型: {}, 可用工具: {}", targetType,
                filteredTools.stream().map(t -> t.getDefinition().getName()).toList());
        return new AgentToolContext(filteredTools, toolDefinitions, knowledgeSearchTool, targetType);
    }

    /**
     * 清理工具运行时状态。
     *
     * <p>当前主要用于清理 {@link KnowledgeSearchTool} 的会话级动态配置，避免污染下一轮调用。</p>
     *
     * @param context 工具上下文，可为 null
     */
    public void clearRuntimeState(AgentToolContext context) {
        if (context == null || context.knowledgeSearchTool() == null) {
            return;
        }
        context.knowledgeSearchTool().clearCurrentKbIds();
        context.knowledgeSearchTool().clearDynamicDescription();
    }

    /**
     * 按配置合并 MCP 动态工具，并与内置工具按原始名称去重。
     *
     * @param customTools 已过滤的内置工具
     * @param sessionId   当前会话 ID
     * @return 合并后的工具列表
     */
    private List<Tool> mergeMcpTools(List<Tool> customTools, String sessionId) {
        if (!mcpEnabled) {
            return customTools;
        }

        Set<String> customNames = customTools.stream()
                .map(t -> t.getDefinition().getName())
                .collect(Collectors.toSet());

        List<Tool> result = new ArrayList<>(customTools);
        int skipped = 0;
        for (Tool mcpTool : mcpToolProvider.getTools(sessionId)) {
            if (mcpTool instanceof RealMcpTool mcp) {
                String originalName = mcp.getOriginalName();
                if (customNames.contains(originalName)) {
                    log.debug("MCP 工具 {} (原始名: {}) 与自定义工具冲突，使用自定义版本",
                            mcpTool.getDefinition().getName(), originalName);
                    skipped++;
                    continue;
                }
            }
            result.add(mcpTool);
        }

        if (skipped > 0) {
            log.info("MCP 工具去重: 跳过 {} 个冲突工具，保留 {} 个 MCP 工具",
                    skipped, result.size() - customTools.size());
        }
        return result;
    }

    /**
     * 根据当前请求的网络搜索开关过滤 web_search 工具。
     *
     * @param tools 待过滤工具
     * @return 过滤后的工具列表
     */
    private List<Tool> filterWebSearchTool(List<Tool> tools) {
        if (UserContext.isWebSearchEnabled()) {
            log.debug("网络搜索已启用");
            return tools;
        }
        List<Tool> filtered = tools.stream()
                .filter(t -> !(t instanceof WebSearchTool))
                .toList();
        if (filtered.size() < tools.size()) {
            log.debug("网络搜索已关闭，web_search 工具已移除");
        }
        return filtered;
    }

    /**
     * 根据子代理总开关过滤 run_subagent 工具。
     *
     * @param tools 待过滤工具
     * @return 过滤后的工具列表
     */
    private List<Tool> filterSubAgentTool(List<Tool> tools) {
        if (subAgentConfig != null && subAgentConfig.isEnabled()) {
            log.info("子代理已启用，run_subagent 工具可用");
            return tools;
        }
        List<Tool> filtered = tools.stream()
                .filter(t -> !(t instanceof RunSubagentTool))
                .toList();
        if (filtered.size() < tools.size()) {
            log.info("子代理未启用，run_subagent 工具已移除");
        }
        return filtered;
    }

    /**
     * 根据本轮知识库开关和 Agent 关联关系过滤 knowledge_search 工具。
     *
     * <p>关闭知识库或当前 Agent 未关联知识库时，从工具实例与工具定义中同时移除，
     * 避免模型绕过前端开关主动发起检索。</p>
     *
     * @param tools 待过滤工具
     * @param app 当前 Agent 应用；可为 null
     * @return 符合本轮知识库开关状态的工具列表
     */
    private List<Tool> filterKnowledgeSearchTool(List<Tool> tools, AgentAppEntity app) {
        boolean available = UserContext.isKnowledgeEnabled()
                && app != null
                && !app.getKnowledgeBaseIds().isEmpty();
        if (available) {
            return tools;
        }
        List<Tool> filtered = tools.stream()
                .filter(tool -> !(tool instanceof KnowledgeSearchTool))
                .toList();
        if (filtered.size() < tools.size()) {
            log.debug("知识库未启用或未关联，knowledge_search 工具已移除");
        }
        return filtered;
    }

    /**
     * 为知识库检索工具设置当前应用允许访问的完整知识库集合。
     *
     * @param tools 当前可用工具
     * @param app   当前应用；可为 null
     * @return 被配置的知识库工具；没有关联知识库时返回 null
     */
    private KnowledgeSearchTool configureKnowledgeSearchTool(List<Tool> tools, AgentAppEntity app) {
        if (app == null || app.getKnowledgeBaseIds().isEmpty()) {
            return null;
        }
        for (Tool tool : tools) {
            if (tool instanceof KnowledgeSearchTool knowledgeSearchTool) {
                knowledgeSearchTool.setCurrentKbIds(app.getKnowledgeBaseIds());
                return knowledgeSearchTool;
            }
        }
        return null;
    }

    /**
     * 构建发送给 LLM 的工具定义。
     *
     * @param tools         当前可用工具
     * @param app           当前应用；可为 null
     * @param kbDescription 知识库描述；可为 null
     * @return 工具定义列表
     */
    private List<ToolDefinition> buildToolDefinitions(List<Tool> tools, AgentAppEntity app, String kbDescription) {
        return tools.stream()
                .map(t -> {
                    if (t instanceof KnowledgeSearchTool knowledgeSearchTool && app != null && kbDescription != null) {
                        return knowledgeSearchTool.getDefinitionWithDescription(kbDescription);
                    }
                    return t.getDefinition();
                })
                .toList();
    }
}
