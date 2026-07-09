package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轻量对话路由器，用于在 Agent 编排前识别确定无需规划模型的社交型输入。
 *
 * @deprecated 请使用 {@link TurnModeClassifier} + {@link TurnPolicyResolver} 组合，
 *             它们提供更细粒度的策略控制（工具、工作区、知识库、StopHook），
 *             不再只是一个 boolean。
 */
@Deprecated
@Component
public class LightweightChatRouter {

    private final TurnModeClassifier classifier;

    public LightweightChatRouter(TurnModeClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 根据用户输入和历史上下文决定是否可以跳过规划阶段。
     *
     * @param userMessage 用户当前输入，允许为空
     * @param history     当前会话历史，用于识别上下文依赖输入
     * @return 可跳过 PlanAgent 时返回 true，否则返回 false
     * @deprecated 请使用 {@link TurnPolicyResolver#resolve(String, List)}
     */
    @Deprecated
    public boolean shouldSkipPlanning(String userMessage, List<ChatMessage> history) {
        return classifier.classify(userMessage, history) == TurnMode.SOCIAL;
    }
}
