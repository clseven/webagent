package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.LlmService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * ReactAgent 工厂。
 *
 * <p>负责创建同步/流式执行器并装配 Hook，避免编排层直接维护构造参数和 Hook 注册细节。</p>
 */
@Service
public class ReactAgentFactory {

    /** 执行模型服务。 */
    private final LlmService executorLlm;

    /** 对话服务，流式执行器用于保存助手消息。 */
    private final ConversationService conversationService;

    /** 后台任务管理器。 */
    private final BackgroundTaskManager backgroundTaskManager;

    /** Hook 装配服务。 */
    private final ReactAgentHookService hookService;

    /**
     * 创建执行器工厂。
     *
     * @param executorLlm           执行模型服务
     * @param conversationService   对话服务
     * @param backgroundTaskManager 后台任务管理器
     * @param hookService           Hook 装配服务
     */
    public ReactAgentFactory(@Qualifier("executorLlm") LlmService executorLlm,
                             ConversationService conversationService,
                             BackgroundTaskManager backgroundTaskManager,
                             ReactAgentHookService hookService) {
        this.executorLlm = executorLlm;
        this.conversationService = conversationService;
        this.backgroundTaskManager = backgroundTaskManager;
        this.hookService = hookService;
    }

    /**
     * 创建同步对话执行器。
     *
     * @param context 单轮对话上下文
     * @param plan    规划文本；可为 null
     * @return 已装配 Hook 的执行器
     */
    public ReactAgent createForChat(AgentTurnContext context, String plan) {
        ReactAgent reactAgent = new ReactAgent(
                executorLlm,
                context.toolContext().filteredTools(),
                context.systemPrompt(),
                plan,
                null,
                null,
                backgroundTaskManager
        );
        hookService.configureForChat(reactAgent, context.toolContext().filteredTools(),
                context.userMessage(), plan);
        return reactAgent;
    }

    /**
     * 创建流式对话执行器。
     *
     * @param context 单轮对话上下文
     * @param plan    规划文本；可为 null
     * @return 已装配 Hook 的执行器
     */
    public ReactAgent createForStream(AgentTurnContext context, String plan) {
        ReactAgent reactAgent = new ReactAgent(
                executorLlm,
                context.toolContext().filteredTools(),
                context.systemPrompt(),
                plan,
                conversationService,
                context.sessionId(),
                backgroundTaskManager
        );
        hookService.configureForStream(reactAgent, context.toolContext().filteredTools(),
                context.userMessage(), plan);
        return reactAgent;
    }
}
