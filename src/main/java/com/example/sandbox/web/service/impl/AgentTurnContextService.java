package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.WorkspaceDirectoryMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 单轮对话上下文准备服务。
 *
 * <p>负责把同步和流式入口完全相同的前置准备收敛到一处，包括历史、沙箱、用户消息入库、
 * 文件上下文、工作区目录记忆、应用知识库、工具上下文和规划技能元数据。</p>
 */
@Service
public class AgentTurnContextService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnContextService.class);

    /** 对话服务，用于读取历史并保存本轮用户消息。 */
    private final ConversationServiceImpl conversationService;

    /** 沙箱服务，用于确保当前会话沙箱就绪。 */
    private final SandboxService sandboxService;

    /** 轻量对话路由器，用于决定是否跳过规划器。 */
    private final LightweightChatRouter lightweightChatRouter;

    /** 工作区目录记忆服务。 */
    private final WorkspaceDirectoryMemoryService workspaceDirectoryMemoryService;

    /** 知识库上下文服务。 */
    private final AgentKnowledgeContextService knowledgeContextService;

    /** 工具上下文服务。 */
    private final AgentToolContextService toolContextService;

    /** 技能运行时服务。 */
    private final AgentSkillRuntimeService skillRuntimeService;

    /**
     * 创建单轮上下文准备服务。
     *
     * @param conversationService            对话服务
     * @param sandboxService                 沙箱服务
     * @param lightweightChatRouter          轻量对话路由器
     * @param workspaceDirectoryMemoryService 工作区目录记忆服务
     * @param knowledgeContextService        知识库上下文服务
     * @param toolContextService             工具上下文服务
     * @param skillRuntimeService            技能运行时服务
     */
    public AgentTurnContextService(ConversationServiceImpl conversationService,
                                   SandboxService sandboxService,
                                   LightweightChatRouter lightweightChatRouter,
                                   WorkspaceDirectoryMemoryService workspaceDirectoryMemoryService,
                                   AgentKnowledgeContextService knowledgeContextService,
                                   AgentToolContextService toolContextService,
                                   AgentSkillRuntimeService skillRuntimeService) {
        this.conversationService = conversationService;
        this.sandboxService = sandboxService;
        this.lightweightChatRouter = lightweightChatRouter;
        this.workspaceDirectoryMemoryService = workspaceDirectoryMemoryService;
        this.knowledgeContextService = knowledgeContextService;
        this.toolContextService = toolContextService;
        this.skillRuntimeService = skillRuntimeService;
    }

    /**
     * 准备当前轮对话上下文。
     *
     * @param session     当前会话，调用方需先完成归属校验
     * @param userMessage 用户消息
     * @param streamMode  是否来自流式入口，用于日志区分
     * @return 单轮对话上下文
     */
    public AgentTurnContext prepare(ConversationSession session, String userMessage, boolean streamMode) {
        String sessionId = session.getSessionId();
        String logPrefix = streamMode ? "【Stream " : "【";
        List<ChatMessage> history = conversationService.getRecentHistory(sessionId, 20);
        log.info("{}历史消息】会话: {} 条数: {}", logPrefix, sessionId, history.size());

        boolean skipPlanningByLightweightRoute = lightweightChatRouter.shouldSkipPlanning(userMessage, history);
        boolean shouldRunPlanAgent = UserContext.isPlanningEnabled() && !skipPlanningByLightweightRoute;
        ensureSandboxReady(sessionId);
        boolean firstTurn = history.isEmpty();

        conversationService.addUserMessage(sessionId, userMessage);
        log.info("{}用户输入】会话: {} 内容: {}", logPrefix, sessionId, userMessage);

        String extraContext = extractFileContext(userMessage);
        String skillPrompt = skillRuntimeService.buildEnabledSkillPrompt(sessionId);
        String workspaceMemoryContext = buildWorkspaceDirectoryMemoryContext(sessionId);
        String systemPrompt = prependContext(workspaceMemoryContext, skillPrompt);
        if (extraContext != null) {
            systemPrompt = prependContext(extraContext, systemPrompt);
        }

        AgentAppEntity app = knowledgeContextService.loadApp(session);
        String kbDescription = knowledgeContextService.buildKnowledgeDescription(app);
        AgentToolContext toolContext = toolContextService.build(sessionId, app, kbDescription);

        Long userId = UserContext.getCurrentUserId();
        String enhancedContext = knowledgeContextService.enhance(
                userId, app, userMessage, history, streamMode ? "【Stream 知识库增强】" : "【知识库增强】");
        if (!enhancedContext.isEmpty()) {
            systemPrompt = prependContext(enhancedContext, systemPrompt);
        }
        log.info("{}系统提示】会话: {} 长度: {} 字符", logPrefix, sessionId, systemPrompt.length());

        List<Skill> planningSkills = skillRuntimeService.findPlanningSkills(sessionId, app);
        String plannerSessionContext = skillRuntimeService.buildSessionContext(
                session, prependContext(workspaceMemoryContext, enhancedContext));

        return new AgentTurnContext(
                session,
                userMessage,
                history,
                firstTurn,
                shouldRunPlanAgent,
                skipPlanningByLightweightRoute,
                userId,
                app,
                systemPrompt,
                workspaceMemoryContext,
                enhancedContext,
                plannerSessionContext,
                planningSkills,
                toolContext
        );
    }

    /**
     * 确保当前会话沙箱就绪。
     *
     * @param sessionId 会话 ID
     */
    private void ensureSandboxReady(String sessionId) {
        if (!sandboxService.hasSandbox(sessionId)) {
            log.info("Sandbox not ready for session {}, creating now...", sessionId);
            sandboxService.createSandbox(sessionId);
        }
    }

    /**
     * 刷新并构建工作区目录记忆上下文。
     *
     * <p>目录记忆是增强信息，失败不能阻断主对话链路，因此只记录 WARN 并返回空字符串。</p>
     *
     * @param sessionId 会话 ID
     * @return 工作区目录记忆上下文
     */
    private String buildWorkspaceDirectoryMemoryContext(String sessionId) {
        try {
            workspaceDirectoryMemoryService.refresh(sessionId);
            return workspaceDirectoryMemoryService.buildContext(sessionId);
        } catch (Exception e) {
            log.warn("工作区目录记忆注入失败: session={}, error={}", sessionId, e.getMessage(), e);
            return "";
        }
    }

    /**
     * 将额外上下文放到基础上下文之前。
     *
     * @param extraContext 额外上下文
     * @param baseContext  基础上下文
     * @return 合并后的上下文
     */
    private String prependContext(String extraContext, String baseContext) {
        if (extraContext == null || extraContext.isBlank()) {
            return baseContext != null ? baseContext : "";
        }
        if (baseContext == null || baseContext.isBlank()) {
            return extraContext;
        }
        return extraContext + "\n\n" + baseContext;
    }

    /**
     * 从用户消息中提取上传文件上下文。
     *
     * @param userMessage 用户消息
     * @return 文件上下文；没有上传文件段落时返回 null
     */
    private String extractFileContext(String userMessage) {
        if (!userMessage.contains("【上传的文件】")) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        context.append("## 用户已上传的文件\n");
        context.append("文件已同步到沙盒 `/home/gem/uploads/` 目录，可直接读取：\n\n");

        String[] lines = userMessage.split("\n");
        for (String line : lines) {
            if (line.contains("📎")) {
                String filename = line.replace("📎", "").trim();
                int sizeIdx = filename.lastIndexOf(" (");
                if (sizeIdx > 0) {
                    filename = filename.substring(0, sizeIdx);
                }
                filename = filename.replaceFirst("^[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "");
                if (!filename.isEmpty()) {
                    context.append("- ").append(filename).append("\n");
                }
            }
        }

        return context.toString();
    }
}
