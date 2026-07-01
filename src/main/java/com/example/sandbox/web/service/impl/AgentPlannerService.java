package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.PlanResult;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.TokenUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Agent 规划阶段服务。
 *
 * <p>负责根据单轮上下文调用 {@link PlanAgent}，并统一记录规划阶段 token 用量。</p>
 */
@Service
public class AgentPlannerService {

    private static final Logger log = LoggerFactory.getLogger(AgentPlannerService.class);

    /** 规划模型服务。 */
    private final LlmService executorLlm;

    /** Token 用量记录服务。 */
    private final TokenUsageService tokenUsageService;

    /**
     * 创建规划阶段服务。
     *
     * @param executorLlm       执行器 LLM；当前规划器沿用执行器模型
     * @param tokenUsageService Token 用量服务
     */
    public AgentPlannerService(@Qualifier("executorLlm") LlmService executorLlm,
                               TokenUsageService tokenUsageService) {
        this.executorLlm = executorLlm;
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * 按上下文决定是否运行规划器，并返回规划文本。
     *
     * @param context        单轮对话上下文
     * @param userMessage    用户消息
     * @param usageType      token 用量记录类型，如 {@code plan} 或 {@code plan_stream}
     * @param resultLogLabel 规划结果日志标签
     * @param previewLength  日志中展示规划内容的最大长度
     * @return 规划文本；跳过规划时返回 null
     */
    public String plan(AgentTurnContext context, String userMessage,
                       String usageType, String resultLogLabel, int previewLength) {
        if (!context.shouldRunPlanAgent()) {
            log.info("【规划跳过】会话: {}, planningEnabled={}, lightweight={}",
                    context.sessionId(), UserContext.isPlanningEnabled(), context.skipPlanningByLightweightRoute());
            return null;
        }

        PlanAgent planAgent = new PlanAgent(
                executorLlm,
                context.toolContext().toolDefinitions(),
                context.planningSkills(),
                context.plannerSessionContext()
        );
        PlanResult planResult = planAgent.plan(userMessage, context.history());
        String plan = planResult.getPlan();
        log.info("{}{}", resultLogLabel, truncate(plan, previewLength));
        recordPlanUsage(context, planResult.getTokenUsage(), usageType);
        return plan;
    }

    /**
     * 记录规划阶段 token 用量。
     *
     * @param context   单轮对话上下文
     * @param usage     模型返回的 token 用量
     * @param usageType 用量记录类型
     */
    private void recordPlanUsage(AgentTurnContext context, LlmUsage usage, String usageType) {
        if (usage == null) {
            return;
        }
        tokenUsageService.record(context.userId(), context.sessionId(), usage.promptTokens(),
                usage.completionTokens(), usage.cacheHitTokens(), usage.totalTokens(), "planner", usageType);
    }

    /**
     * 截断日志预览文本。
     *
     * @param value         原始文本
     * @param previewLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String value, int previewLength) {
        if (value == null || value.length() <= previewLength) {
            return value;
        }
        return value.substring(0, previewLength) + "...";
    }
}
