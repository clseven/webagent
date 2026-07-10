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
 *
 * <p>自 TurnPolicy 升级后，本轮策略（{@link TurnPolicy}）控制各阶段是否组装：
 * SOCIAL 轮次跳过工具、工作区、知识库和 StopHook；TASK/AMBIGUOUS 保持全能力。</p>
 */
@Service
public class AgentTurnContextService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnContextService.class);

    private final ConversationServiceImpl conversationService;
    private final SandboxService sandboxService;
    private final LightweightChatRouter lightweightChatRouter;
    private final TurnPolicyResolver turnPolicyResolver;
    private final WorkspaceDirectoryMemoryService workspaceDirectoryMemoryService;
    private final AgentKnowledgeContextService knowledgeContextService;
    private final AgentToolContextService toolContextService;
    private final AgentSkillRuntimeService skillRuntimeService;
    private final AgentPlannerService agentPlannerService;

    public AgentTurnContextService(ConversationServiceImpl conversationService,
                                   SandboxService sandboxService,
                                   LightweightChatRouter lightweightChatRouter,
                                   TurnPolicyResolver turnPolicyResolver,
                                   WorkspaceDirectoryMemoryService workspaceDirectoryMemoryService,
                                   AgentKnowledgeContextService knowledgeContextService,
                                   AgentToolContextService toolContextService,
                                   AgentSkillRuntimeService skillRuntimeService,
                                   AgentPlannerService agentPlannerService) {
        this.conversationService = conversationService;
        this.sandboxService = sandboxService;
        this.lightweightChatRouter = lightweightChatRouter;
        this.turnPolicyResolver = turnPolicyResolver;
        this.workspaceDirectoryMemoryService = workspaceDirectoryMemoryService;
        this.knowledgeContextService = knowledgeContextService;
        this.toolContextService = toolContextService;
        this.skillRuntimeService = skillRuntimeService;
        this.agentPlannerService = agentPlannerService;
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

        TurnPolicy policy = TurnPolicy.forMode(agentPlannerService.judgeIntent(userMessage, history));
        log.info("{}轮次策略】会话: {} mode={} plan={} tools={} skill={} ws={} kb={} stopHook={}",
                logPrefix, sessionId, policy.mode(),
                policy.shouldPlan(), policy.shouldGiveTools(), policy.shouldInjectSkill(),
                policy.shouldInjectWorkspace(), policy.shouldInjectKB(),
                policy.shouldEnableStopHook());

        boolean skipPlanningByLightweightRoute = lightweightChatRouter.shouldSkipPlanning(userMessage, history);
        boolean shouldRunPlanAgent = UserContext.isPlanningEnabled() && !skipPlanningByLightweightRoute;
        ensureSandboxReady(sessionId);
        boolean firstTurn = history.isEmpty();

        conversationService.addUserMessage(sessionId, userMessage);
        log.info("{}用户输入】会话: {} 内容: {}", logPrefix, sessionId, userMessage);

        String extraContext = extractFileContext(userMessage);
        String skillPrompt = policy.shouldInjectSkill()
                ? skillRuntimeService.buildEnabledSkillPrompt(sessionId)
                : "";

        String workspaceMemoryContext = policy.shouldInjectWorkspace()
                ? buildWorkspaceDirectoryMemoryContext(sessionId)
                : "";
        String systemPrompt = prependContext(workspaceMemoryContext, skillPrompt);
        if (extraContext != null) {
            systemPrompt = prependContext(extraContext, systemPrompt);
        }

        AgentAppEntity app = knowledgeContextService.loadApp(session);
        String kbDescription = knowledgeContextService.buildKnowledgeDescription(app);
        AgentToolContext toolContext = policy.shouldGiveTools()
                ? toolContextService.build(sessionId, app, kbDescription)
                : AgentToolContext.empty();

        Long userId = UserContext.getCurrentUserId();
        String enhancedContext = policy.shouldInjectKB()
                ? knowledgeContextService.enhance(
                        userId, app, userMessage, history, streamMode ? "【Stream 知识库增强】" : "【知识库增强】")
                : "";
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
                policy,
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

    private void ensureSandboxReady(String sessionId) {
        if (!sandboxService.hasSandbox(sessionId)) {
            log.info("Sandbox not ready for session {}, creating now...", sessionId);
            sandboxService.createSandbox(sessionId);
        }
    }

    private String buildWorkspaceDirectoryMemoryContext(String sessionId) {
        try {
            workspaceDirectoryMemoryService.refresh(sessionId);
            return workspaceDirectoryMemoryService.buildContext(sessionId);
        } catch (Exception e) {
            log.warn("工作区目录记忆注入失败: session={}, error={}", sessionId, e.getMessage(), e);
            return "";
        }
    }

    private String prependContext(String extraContext, String baseContext) {
        if (extraContext == null || extraContext.isBlank()) {
            return baseContext != null ? baseContext : "";
        }
        if (baseContext == null || baseContext.isBlank()) {
            return extraContext;
        }
        return extraContext + "\n\n" + baseContext;
    }

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
