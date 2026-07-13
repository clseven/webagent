package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.WorkspaceDirectoryMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 编排中的工作区目录记忆上下文测试。
 */
class AgentServiceWorkspaceMemoryTest {

    /**
     * 验证目录记忆摘要会进入执行器系统提示和规划器会话上下文。
     */
    @Test
    void appendsWorkspaceDirectoryMemoryContextToTurnContext() {
        String userMessage = "请总结工作区文件";
        ConversationSession session = new ConversationSession();
        String sessionId = session.getSessionId();
        String workspaceMemoryContext = "## 工作区目录记忆\n- uploads/report.pdf (12 B)\n";

        ConversationServiceImpl conversationService = mock(ConversationServiceImpl.class);
        SandboxService sandboxService = mock(SandboxService.class);
        LightweightChatRouter lightweightChatRouter = mock(LightweightChatRouter.class);
        WorkspaceDirectoryMemoryService memoryService = mock(WorkspaceDirectoryMemoryService.class);
        AgentKnowledgeContextService knowledgeContextService = mock(AgentKnowledgeContextService.class);
        AgentToolContextService toolContextService = mock(AgentToolContextService.class);
        AgentSkillRuntimeService skillRuntimeService = mock(AgentSkillRuntimeService.class);
        AgentPlannerService agentPlannerService = mock(AgentPlannerService.class);

        when(conversationService.getRecentHistory(sessionId, 20)).thenReturn(List.of());
        when(agentPlannerService.judgeIntent(userMessage, List.of())).thenReturn(TurnMode.TASK);
        when(sandboxService.hasSandbox(sessionId)).thenReturn(true);
        when(lightweightChatRouter.shouldSkipPlanning(userMessage, List.of())).thenReturn(false);
        when(memoryService.buildContext(sessionId)).thenReturn(workspaceMemoryContext);
        when(skillRuntimeService.buildEnabledSkillPrompt(sessionId)).thenReturn("基础系统提示");
        when(skillRuntimeService.findPlanningSkills(sessionId, null)).thenReturn(List.of());
        when(skillRuntimeService.buildSessionContext(eq(session), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(knowledgeContextService.loadApp(session)).thenReturn(null);
        when(knowledgeContextService.buildKnowledgeDescription(null)).thenReturn(null);
        when(knowledgeContextService.enhance(eq(7L), isNull(), eq(userMessage), anyList(), anyString()))
                .thenReturn("");
        when(toolContextService.build(eq(sessionId), isNull(), isNull()))
                .thenReturn(new AgentToolContext(List.of(), List.of(), null, "COMMON"));

        AgentTurnContextService contextService = new AgentTurnContextService(
                conversationService,
                sandboxService,
                lightweightChatRouter,
                new TurnPolicyResolver(new TurnModeClassifier()),
                memoryService,
                knowledgeContextService,
                toolContextService,
                skillRuntimeService,
                agentPlannerService
        );

        try {
            UserContext.setCurrentUserId(7L);

            AgentTurnContext context = contextService.prepare(session, userMessage, false);

            assertThat(context.systemPrompt()).startsWith(workspaceMemoryContext);
            assertThat(context.systemPrompt()).contains("uploads/report.pdf (12 B)");
            assertThat(context.systemPrompt()).endsWith("基础系统提示");
            assertThat(context.plannerSessionContext()).contains(workspaceMemoryContext);
            verify(memoryService).refresh(sessionId);
            verify(conversationService).addUserMessage(sessionId, userMessage);
        } finally {
            UserContext.clear();
        }
    }

}
