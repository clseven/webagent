package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.entity.Skill;

import java.util.List;

/**
 * Agent 单轮对话的共享上下文。
 *
 * <p>同步和流式入口都使用该上下文，避免在两个入口重复准备系统提示、工具、
 * 知识库、技能信息和本轮用户请求。</p>
 *
 * @param session 当前会话
 * @param userMessage 本轮用户原始请求
 * @param executionUserMessage 仅供本轮模型执行使用的用户消息；可能在原始请求前包含知识库参考资料
 * @param history 本轮用户消息入库前的历史消息
 * @param continuationContext 上轮暂停运行的模型可读续接资料；没有时为空字符串
 * @param firstTurn 当前轮是否为会话首轮
 * @param shouldRunPlanAgent 是否需要调用规划器
 * @param skipPlanningByLightweightRoute 是否因为轻量路由跳过规划
 * @param policy 本轮策略开关，控制工具、技能、工作区和 StopHook；知识库由独立请求开关控制
 * @param userId 当前用户 ID
 * @param app 当前会话关联的 Agent 应用；没有或加载失败时为 null
 * @param systemPrompt 执行器系统提示词
 * @param runtimeTimeContext 本轮不可变时间上下文，供规划器、执行器和子智能体共享
 * @param workspaceMemoryContext 工作区目录记忆上下文
 * @param enhancedContext 知识库增强上下文
 * @param plannerSessionContext 发送给规划器的会话上下文
 * @param planningSkills 发送给规划器的技能元数据
 * @param toolContext 当前会话工具上下文
 */
public record AgentTurnContext(ConversationSession session,
                               String userMessage,
                               String executionUserMessage,
                               List<ChatMessage> history,
                               String continuationContext,
                               boolean firstTurn,
                               @Deprecated boolean shouldRunPlanAgent,
                               @Deprecated boolean skipPlanningByLightweightRoute,
                               TurnPolicy policy,
                               Long userId,
                               AgentAppEntity app,
                               String systemPrompt,
                               String runtimeTimeContext,
                               String workspaceMemoryContext,
                               String enhancedContext,
                               String plannerSessionContext,
                               List<Skill> planningSkills,
                               AgentToolContext toolContext) {

    /**
     * 获取当前会话 ID。
     *
     * @return 会话 ID
     */
    public String sessionId() {
        return session.getSessionId();
    }
}
