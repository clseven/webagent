package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.llm.AgentContinuation;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.WorkspaceDirectoryMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证单轮上下文准备会恢复新检查点并兼容旧超限消息。
 */
class AgentTurnContinuationContextTest {

    /**
     * 精确检查点应替换普通最近历史，并同时进入规划器和执行器上下文。
     */
    @Test
    void 精确检查点应优先于最近二十条历史() {
        Fixture fixture = new Fixture();
        List<ChatMessage> checkpoint = List.of(
                ChatMessage.userMessage("读取文件"),
                ChatMessage.assistantToolCallMessage(
                        new LlmToolCall("call_1", "read_file", Map.of("path", "/a"))),
                ChatMessage.toolMessage("call_1", "文件内容"));
        String continuationContext = "## 上轮暂停运行续接资料\n已读取 /a";
        when(fixture.conversationService.getLatestContinuation(fixture.sessionId))
                .thenReturn(new AgentContinuation(checkpoint, continuationContext, true, true));

        AgentTurnContext context = fixture.prepare("继续");

        assertThat(context.history()).containsExactlyElementsOf(checkpoint);
        assertThat(context.continuationContext()).isEqualTo(continuationContext);
        assertThat(context.systemPrompt()).contains(continuationContext);
        assertThat(context.plannerSessionContext()).contains(continuationContext);
    }

    /**
     * 旧超限事件没有精确检查点时，应移除空洞超限提示并注入事件续接资料。
     */
    @Test
    void 旧事件续接应移除最后一条超限提示() {
        Fixture fixture = new Fixture();
        ChatMessage previousUser = ChatMessage.userMessage("分析项目");
        ChatMessage limitMessage = ChatMessage.assistantMessage(
                ConversationServiceImpl.STREAM_ITERATION_LIMIT_MESSAGE);
        when(fixture.conversationService.getRecentHistory(fixture.sessionId, 20))
                .thenReturn(List.of(previousUser, limitMessage));
        String continuationContext = "## 上轮暂停运行续接资料\n已完成代码搜索";
        when(fixture.conversationService.getLatestContinuation(fixture.sessionId))
                .thenReturn(new AgentContinuation(List.of(), continuationContext, false, true));

        AgentTurnContext context = fixture.prepare("继续");

        assertThat(context.history()).containsExactly(previousUser);
        assertThat(context.history()).noneMatch(message ->
                ConversationServiceImpl.STREAM_ITERATION_LIMIT_MESSAGE.equals(message.getContent()));
        assertThat(context.systemPrompt()).contains(continuationContext);
    }

    /**
     * 知识库开关开启时，SOCIAL 轻量轮次也必须先检索所有关联知识库。
     */
    @Test
    void 知识库开启时社交轮次也应执行检索() {
        Fixture fixture = new Fixture();
        AgentAppEntity app = new AgentAppEntity();
        app.setKnowledgeBaseIds(Set.of(11L, 12L));
        when(fixture.agentPlannerService.judgeIntent(anyString(), anyList())).thenReturn(TurnMode.SOCIAL);
        when(fixture.knowledgeContextService.loadApp(fixture.session)).thenReturn(app);
        when(fixture.knowledgeContextService.buildKnowledgeDescription(app)).thenReturn("两个知识库");
        when(fixture.knowledgeContextService.enhance(
                eq(7L), eq(app), eq("你好"), anyList(), anyString()))
                .thenReturn("知识库命中内容");

        AgentTurnContext context = fixture.prepare("你好", true);

        verify(fixture.knowledgeContextService).enhance(
                eq(7L), eq(app), eq("你好"), anyList(), anyString());
        assertThat(context.executionUserMessage()).contains("知识库命中内容");
        assertThat(context.systemPrompt()).doesNotContain("知识库命中内容");
    }

    /**
     * 知识库开关关闭时，即使是完整任务策略也不得执行自动检索。
     */
    @Test
    void 知识库关闭时任务轮次也不应执行检索() {
        Fixture fixture = new Fixture();
        AgentAppEntity app = new AgentAppEntity();
        app.setKnowledgeBaseIds(Set.of(11L, 12L));
        when(fixture.knowledgeContextService.loadApp(fixture.session)).thenReturn(app);

        AgentTurnContext context = fixture.prepare("分析项目", false);

        verify(fixture.knowledgeContextService, never()).enhance(
                eq(7L), eq(app), anyString(), anyList(), anyString());
        assertThat(context.enhancedContext()).isEmpty();
    }

    /**
     * 封装上下文服务测试所需的协作者和默认行为。
     */
    private static final class Fixture {
        private final ConversationSession session = new ConversationSession();
        private final String sessionId = session.getSessionId();
        private final ConversationServiceImpl conversationService = mock(ConversationServiceImpl.class);
        private final SandboxService sandboxService = mock(SandboxService.class);
        private final LightweightChatRouter lightweightChatRouter = mock(LightweightChatRouter.class);
        private final WorkspaceDirectoryMemoryService memoryService = mock(WorkspaceDirectoryMemoryService.class);
        private final AgentKnowledgeContextService knowledgeContextService = mock(AgentKnowledgeContextService.class);
        private final AgentToolContextService toolContextService = mock(AgentToolContextService.class);
        private final AgentSkillRuntimeService skillRuntimeService = mock(AgentSkillRuntimeService.class);
        private final AgentPlannerService agentPlannerService = mock(AgentPlannerService.class);
        private final AgentTurnContextService service;

        /**
         * 创建默认走 TASK 策略且不依赖真实沙箱的测试夹具。
         */
        private Fixture() {
            when(conversationService.getRecentHistory(sessionId, 20)).thenReturn(List.of());
            when(sandboxService.hasSandbox(sessionId)).thenReturn(true);
            when(agentPlannerService.judgeIntent(anyString(), anyList())).thenReturn(TurnMode.TASK);
            when(lightweightChatRouter.shouldSkipPlanning(anyString(), anyList())).thenReturn(false);
            when(memoryService.buildContext(sessionId)).thenReturn("");
            when(skillRuntimeService.buildEnabledSkillPrompt(sessionId)).thenReturn("基础系统提示");
            when(skillRuntimeService.findPlanningSkills(sessionId, null)).thenReturn(List.of());
            when(skillRuntimeService.buildSessionContext(eq(session), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(knowledgeContextService.loadApp(session)).thenReturn(null);
            when(knowledgeContextService.buildKnowledgeDescription(null)).thenReturn(null);
            when(knowledgeContextService.enhance(eq(7L), isNull(), anyString(), anyList(), anyString()))
                    .thenReturn("");
            when(toolContextService.build(eq(sessionId), isNull(), isNull()))
                    .thenReturn(new AgentToolContext(List.of(), List.of(), null, "COMMON"));

            service = new AgentTurnContextService(
                    conversationService,
                    sandboxService,
                    lightweightChatRouter,
                    new TurnPolicyResolver(new TurnModeClassifier()),
                    memoryService,
                    knowledgeContextService,
                    toolContextService,
                    skillRuntimeService,
                    agentPlannerService);
        }

        /**
         * 在固定用户上下文中准备一轮对话。
         *
         * @param userMessage 当前用户消息
         * @return 已准备的单轮上下文
         */
        private AgentTurnContext prepare(String userMessage) {
            return prepare(userMessage, true);
        }

        /**
         * 在固定用户上下文和指定知识库开关状态下准备一轮对话。
         *
         * @param userMessage 当前用户消息
         * @param knowledgeEnabled 是否启用知识库
         * @return 已准备的单轮上下文
         */
        private AgentTurnContext prepare(String userMessage, boolean knowledgeEnabled) {
            try {
                UserContext.setCurrentUserId(7L);
                UserContext.setKnowledgeEnabled(knowledgeEnabled);
                return service.prepare(session, userMessage, true);
            } finally {
                UserContext.clear();
            }
        }
    }
}
