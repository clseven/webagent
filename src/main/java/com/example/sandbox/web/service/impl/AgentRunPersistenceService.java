package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.AgentRunEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.llm.AgentRunStatus;
import com.example.sandbox.web.model.llm.AgentRunTrace;
import com.example.sandbox.web.model.llm.AgentRunCheckpoint;
import com.example.sandbox.web.model.llm.AgentStep;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.repository.AgentRunRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent 单次运行轨迹持久化服务。
 *
 * <p>服务按一次用户请求写入一条 {@code agent_run}，不会为每个工具步骤创建数据库行。
 * JSON 序列化或数据库写入失败时不重试，因为重复写入会破坏“一次请求一条记录”的约束；
 * 异常直接向上抛出，由调用链决定是否终止当前收尾流程。</p>
 */
@Service
public class AgentRunPersistenceService {

    /** Agent 运行记录 Repository。 */
    private final AgentRunRepository agentRunRepository;

    /** 会话 Repository。 */
    private final ConversationSessionRepository sessionRepository;

    /** 运行轨迹 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 本轮模型协议构建器。 */
    private final AgentProtocolBuilder protocolBuilder;

    /** 上下文 token 估算器。 */
    private final ConversationContextTokenEstimator tokenEstimator;

    /** 会话级上下文快照服务。 */
    private final ConversationContextService conversationContextService;

    /**
     * 创建运行轨迹持久化服务。
     *
     * @param agentRunRepository 运行记录 Repository
     * @param sessionRepository  会话 Repository
     * @param objectMapper       JSON 序列化器
     */
    public AgentRunPersistenceService(AgentRunRepository agentRunRepository,
                                      ConversationSessionRepository sessionRepository,
                                      ObjectMapper objectMapper,
                                      AgentProtocolBuilder protocolBuilder,
                                      ConversationContextTokenEstimator tokenEstimator,
                                      ConversationContextService conversationContextService) {
        this.agentRunRepository = agentRunRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.protocolBuilder = protocolBuilder;
        this.tokenEstimator = tokenEstimator;
        this.conversationContextService = conversationContextService;
    }

    /**
     * 保存一次用户请求产生的完整运行结果。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户原始消息
     * @param plan        本轮执行计划，可为空
     * @param steps       本轮工具执行步骤，可为空
     * @param finalAnswer 最终回答或暂停提示，可为空
     * @param status      运行结束状态
     * @param usage       本轮累计 token 用量，可为空
     * @param iterations  ReAct 迭代次数
     * @throws SessionNotFoundException 会话不存在时抛出
     * @throws IllegalArgumentException  运行轨迹无法序列化时抛出
     */
    @Transactional
    public void save(String sessionId, String userMessage, String plan, List<AgentStep> steps, String finalAnswer,
                     AgentRunStatus status, LlmUsage usage, int iterations) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        AgentRunTrace trace = AgentRunTrace.of(plan, steps, finalAnswer);

        AgentRunEntity entity = new AgentRunEntity();
        entity.setSession(session);
        entity.setStatus((status != null ? status : AgentRunStatus.COMPLETED).name());
        entity.setTraceJson(serializeTrace(trace));
        List<com.example.sandbox.web.model.entity.ChatMessage> protocolMessages =
                protocolBuilder.build(userMessage, steps, finalAnswer);
        entity.setProtocolJson(serializeProtocol(protocolMessages));
        entity.setProtocolTokens(tokenEstimator.estimateMessages(protocolMessages));
        entity.setIterations(iterations);
        entity.setPromptTokens(usage != null ? usage.promptTokens() : 0);
        entity.setCompletionTokens(usage != null ? usage.completionTokens() : 0);
        entity.setCacheHitTokens(usage != null ? usage.cacheHitTokens() : 0);
        entity.setTotalTokens(usage != null ? usage.totalTokens() : 0);
        AgentRunEntity saved = agentRunRepository.save(entity);
        try {
            conversationContextService.appendRun(saved);
        } catch (Exception exception) {
            // 原始运行已经落库；快照失败由下一轮按 lastAppliedRunId 补偿，不能覆盖或重复写运行记录。
            org.slf4j.LoggerFactory.getLogger(AgentRunPersistenceService.class)
                    .warn("会话上下文快照更新失败，将在下一轮补偿: session={}, runId={}",
                            sessionId, saved.getId(), exception);
        }
    }

    /**
     * 将运行轨迹序列化为带版本号的 JSON。
     *
     * @param trace 运行轨迹
     * @return JSON 字符串
     * @throws IllegalArgumentException 序列化失败时抛出
     */
    private String serializeTrace(AgentRunTrace trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize agent run trace", e);
        }
    }

    /**
     * 序列化本轮模型协议。
     *
     * @param messages 本轮协议消息
     * @return 带版本号的协议 JSON
     */
    private String serializeProtocol(List<com.example.sandbox.web.model.entity.ChatMessage> messages) {
        try {
            return objectMapper.writeValueAsString(AgentRunCheckpoint.fromMessages(messages));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize agent run protocol", exception);
        }
    }
}
