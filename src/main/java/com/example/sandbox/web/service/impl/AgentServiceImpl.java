package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.entity.*;
import com.example.sandbox.web.model.llm.AgentEventMapper;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.model.response.BatchDeleteSessionsResponse;
import com.example.sandbox.web.model.sse.SseEvent;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.TokenUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Agent 编排服务实现 — 串联规划与执行两大阶段
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>前置处理：加载历史、过滤工具、注入知识库描述</li>
 *   <li>Phase 1 - 规划：调用 PlanAgent 产出执行计划</li>
 *   <li>Phase 2 - 执行：调用 ReactAgent 执行任务（ReAct 循环）</li>
 *   <li>后置处理：保存消息、记录 token 用量</li>
 * </ol>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li>规划与执行解耦 — PlanAgent 只规划不带工具，ReactAgent 只执行不规划</li>
 *   <li>双 LLM — 规划用智谱 GLM，执行用 DeepSeek（按场景选模型）</li>
 *   <li>工具过滤 — 根据沙箱类型（AIO/Common）动态过滤可用工具</li>
 *   <li>知识库注入 — 应用关联知识库时，动态修改工具描述</li>
 * </ul>
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    /**
     * 会话标题生成时发送给模型的用户消息最大字符数。
     */
    private static final int TITLE_USER_MESSAGE_MAX_CODE_POINTS = 600;

    /**
     * 会话标题生成时发送给模型的助手回复最大字符数。
     */
    private static final int TITLE_ASSISTANT_MESSAGE_MAX_CODE_POINTS = 900;

    /**
     * 自动生成标题在会话列表中的最大字符数。
     */
    private static final int GENERATED_TITLE_MAX_CODE_POINTS = 24;

    /** 会话服务（管理消息历史、Session 生命周期） */
    @Autowired
    private ConversationServiceImpl conversationService;

    /** 规划 LLM（智谱 GLM，负责意图理解和任务规划） */
    @Autowired
    @Qualifier("plannerLlm")
    private LlmService plannerLlm;

    /** Token 用量服务（记录每次 LLM 调用的 token 消耗） */
    @Autowired
    private TokenUsageService tokenUsageService;

    /** 沙箱服务（懒加载，避免循环依赖） */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.example.sandbox.web.service.SandboxService sandboxService;

    /** 单轮上下文准备服务。 */
    @Autowired
    private AgentTurnContextService agentTurnContextService;

    /** 规划阶段服务。 */
    @Autowired
    private AgentPlannerService agentPlannerService;

    /** ReactAgent 工厂。 */
    @Autowired
    private ReactAgentFactory reactAgentFactory;

    /** 工具上下文服务，用于清理会话级工具状态。 */
    @Autowired
    private AgentToolContextService agentToolContextService;

    /**
     * 创建新会话（无关联应用）
     *
     * <p>异步创建沙箱，不阻塞 HTTP 响应。</p>
     */
    @Override
    public ConversationSession createSession() {
        ConversationSession session = conversationService.createSession();
        log.info("Created session: {}", session.getSessionId());

        // 异步创建沙箱，不阻塞前端响应
        String sessionId = session.getSessionId();
        CompletableFuture.runAsync(() -> {
            try {
                // 如果数据库中已有沙箱记录，跳过创建
                if (sandboxService.hasSandbox(sessionId)) {
                    log.info("Sandbox already exists for session {}, skipping", sessionId);
                    return;
                }
                sandboxService.createSandbox(sessionId);
                log.info("Sandbox created async for session: {}", sessionId);
            } catch (Exception e) {
                log.warn("Async create sandbox failed for session {}: {}", sessionId, e.getMessage());
            }
        });

        return session;
    }

    /**
     * 创建新会话（关联 Agent 应用）
     *
     * <p>异步创建沙箱，不阻塞 HTTP 响应。</p>
     *
     * @param appId Agent 应用 ID
     */
    @Override
    public ConversationSession createSession(Long appId) {
        ConversationSession session = conversationService.createSession(appId);
        log.info("Created session: {} for app: {}", session.getSessionId(), appId);

        // 异步创建沙箱，不阻塞前端响应
        String sessionId = session.getSessionId();
        CompletableFuture.runAsync(() -> {
            try {
                if (sandboxService.hasSandbox(sessionId)) {
                    log.info("Sandbox already exists for session {}, skipping", sessionId);
                    return;
                }
                sandboxService.createSandbox(sessionId);
                log.info("Sandbox created async for session: {}", sessionId);
            } catch (Exception e) {
                log.warn("Async create sandbox failed for session {}: {}", sessionId, e.getMessage());
            }
        });

        return session;
    }

    /**
     * 删除会话记录及其历史消息。
     *
     * <p>沙箱是用户级资源，删除单个会话时只移除会话数据，不销毁用户沙箱。</p>
     *
     * @param sessionId 会话 ID
     */
    @Override
    public void deleteSession(String sessionId) {
        ConversationSession session = conversationService.getSession(sessionId);
        validateSessionOwnership(session);
        conversationService.deleteSession(sessionId);
        log.info("Deleted session: {}", sessionId);
    }

    /**
     * 批量删除当前用户拥有的会话记录及其历史消息。
     *
     * <p>输入会先去除空值和重复项；不存在或不属于当前用户的 ID 统一作为跳过项返回。
     * 批量删除仍不销毁用户级沙箱。</p>
     *
     * @param sessionIds 待删除的会话 ID
     * @return 实际删除和跳过的会话 ID
     */
    @Override
    public BatchDeleteSessionsResponse deleteSessions(List<String> sessionIds) {
        LinkedHashSet<String> requestedIds = sessionIds == null
                ? new LinkedHashSet<>()
                : sessionIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedIds.isEmpty()) {
            return new BatchDeleteSessionsResponse(List.of(), List.of());
        }

        Long userId = UserContext.getCurrentUserId();
        List<String> deletedIds = conversationService.deleteSessionsOwnedByUser(requestedIds, userId);
        Set<String> deletedIdSet = Set.copyOf(deletedIds);
        List<String> skippedIds = requestedIds.stream()
                .filter(id -> !deletedIdSet.contains(id))
                .toList();
        log.info("Batch deleted sessions: userId={}, deleted={}, skipped={}",
                userId, deletedIds.size(), skippedIds.size());
        return new BatchDeleteSessionsResponse(deletedIds, skippedIds);
    }

    /**
     * 获取会话信息
     */
    @Override
    public ConversationSession getSession(String sessionId) {
        ConversationSession session = conversationService.getSession(sessionId);
        validateSessionOwnership(session);
        return session;
    }

    /**
     * 处理用户对话 — 核心编排入口
     *
     * <h3>完整流程</h3>
     * <ol>
     *   <li>验证会话归属，加载历史消息</li>
     *   <li>确保沙箱已就绪（首次访问时同步创建）</li>
     *   <li>提取文件上下文（用户上传了文件时）</li>
     *   <li>构建系统提示（技能元数据 + 文件上下文）</li>
     *   <li>加载应用配置（知识库描述注入、Skill 过滤）</li>
     *   <li>根据沙箱类型过滤可用工具</li>
     *   <li>Phase 1: 调用 PlanAgent 规划任务</li>
     *   <li>Phase 2: 调用 ReactAgent 执行任务</li>
     *   <li>保存消息、记录 token 用量</li>
     * </ol>
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 助手响应消息
     */
    @Override
    public ChatMessage chat(String sessionId, String userMessage) {
        ConversationSession session = conversationService.getSession(sessionId);
        validateSessionOwnership(session);
        AgentTurnContext context = agentTurnContextService.prepare(session, userMessage, false);
        String plan = agentPlannerService.plan(context, userMessage, "plan", "【规划结果】", 300);
        try {
            ReactAgent reactAgent = reactAgentFactory.createForChat(context, plan);
            AgentResponse agentResponse = reactAgent.run(sessionId, userMessage, context.history());
            String response = agentResponse.getFinalAnswer();
            String reasoning = agentResponse.getFinalReasoning();
            List<Map<String, Object>> events = AgentEventMapper.fromPlanAndSteps(plan, agentResponse.getSteps());

            LlmUsage execUsage = agentResponse.getTotalUsage();
            tokenUsageService.record(context.userId(), sessionId, execUsage.promptTokens(),
                    execUsage.completionTokens(), execUsage.cacheHitTokens(),
                    execUsage.totalTokens(), "executor", "chat");

            conversationService.addAssistantMessage(sessionId, response, reasoning, events);
            scheduleGeneratedTitle(sessionId, context.userId(), context.firstTurn(), userMessage, response);
            log.info("【助手输出】会话: {} 内容长度: {}, 思考链长度: {}",
                    sessionId, response != null ? response.length() : 0, reasoning != null ? reasoning.length() : 0);

            return ChatMessage.restore("assistant", response, reasoning, System.currentTimeMillis(), events);
        } finally {
            agentToolContextService.clearRuntimeState(context.toolContext());
        }
    }

    /**
     * 验证会话归属（防止越权访问其他用户的会话）
     */
    private void validateSessionOwnership(ConversationSession session) {
        Long currentUserId = UserContext.getCurrentUserId();
        Long sessionUserId = session.getUserId();
        if (sessionUserId != null && !sessionUserId.equals(currentUserId)) {
            throw new UnauthorizedException("Session does not belong to current user");
        }
    }

    /**
     * 在首轮对话完成后异步生成会话标题。
     *
     * <p>标题生成失败时只记录日志，不影响会话保存；会话会继续保留默认标题。</p>
     *
     * @param sessionId         会话 ID
     * @param userId            当前用户 ID，用于记录标题生成的 token 用量
     * @param firstTurn         是否为当前会话第一轮对话
     * @param userMessage       第一条用户消息
     * @param assistantResponse 第一条助手回复
     */
    private void scheduleGeneratedTitle(String sessionId, Long userId, boolean firstTurn,
                                        String userMessage, String assistantResponse) {
        if (!firstTurn || assistantResponse == null || assistantResponse.isBlank()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String title = generateSessionTitle(sessionId, userId, userMessage, assistantResponse);
                if (title.isBlank()) {
                    return;
                }
                conversationService.updateGeneratedTitle(sessionId, title);
                log.info("会话标题生成成功: sessionId={}, title={}", sessionId, title);
            } catch (Exception e) {
                log.warn("会话标题生成失败: sessionId={}", sessionId, e);
            }
        });
    }

    /**
     * 调用规划模型生成短会话标题。
     *
     * @param sessionId         会话 ID
     * @param userId            当前用户 ID
     * @param userMessage       第一条用户消息
     * @param assistantResponse 第一条助手回复
     * @return 清洗后的短标题；不可用时返回空字符串
     */
    private String generateSessionTitle(String sessionId, Long userId, String userMessage, String assistantResponse) {
        String systemPrompt = """
                你是会话标题生成器。请根据第一轮用户输入和助手回复生成一个中文短标题。
                要求：只输出标题本身；不要解释；不要加引号；不要超过 20 个汉字或 24 个字符；避免使用“新对话”。
                """;
        String prompt = "第一条用户输入：\n"
                + truncateForTitle(userMessage, TITLE_USER_MESSAGE_MAX_CODE_POINTS)
                + "\n\n第一条助手回复：\n"
                + truncateForTitle(assistantResponse, TITLE_ASSISTANT_MESSAGE_MAX_CODE_POINTS);

        LlmResponse response = plannerLlm.chatWithSystemResponse(systemPrompt, List.of(ChatMessage.userMessage(prompt)));
        recordTitleTokenUsage(userId, sessionId, response.getTokenUsage());
        return sanitizeGeneratedTitle(response.getContent());
    }

    /**
     * 记录标题生成消耗的 token；无用量信息时跳过。
     *
     * @param userId    当前用户 ID
     * @param sessionId 会话 ID
     * @param usage     模型返回的 token 用量
     */
    private void recordTitleTokenUsage(Long userId, String sessionId, LlmUsage usage) {
        if (userId == null || usage == null) {
            return;
        }
        tokenUsageService.record(userId, sessionId, usage.promptTokens(),
                usage.completionTokens(), usage.cacheHitTokens(),
                usage.totalTokens(), "planner", "title");
    }

    /**
     * 截断标题生成上下文，控制额外模型调用成本。
     *
     * @param content       原始内容
     * @param maxCodePoints 最大字符数
     * @return 截断后的内容
     */
    private String truncateForTitle(String content, int maxCodePoints) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= maxCodePoints) {
            return normalized;
        }
        int endIndex = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, endIndex);
    }

    /**
     * 清洗模型返回的标题，避免把解释文字或错误提示写入会话列表。
     *
     * @param rawTitle 模型原始输出
     * @return 可保存标题；不可用时返回空字符串
     */
    private String sanitizeGeneratedTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String title = rawTitle.replaceAll("\\s+", " ").trim();
        title = title.replaceFirst("(?i)^title\\s*[:：]\\s*", "");
        title = title.replaceFirst("^标题\\s*[:：]\\s*", "");
        title = title.replaceAll("^[\"'`“”‘’《]+|[\"'`“”‘’》]+$", "").trim();
        if (title.isBlank()
                || title.startsWith("抱歉")
                || title.startsWith("错误")
                || title.contains("AI 服务暂时不可用")) {
            return "";
        }
        int count = title.codePointCount(0, title.length());
        if (count <= GENERATED_TITLE_MAX_CODE_POINTS) {
            return title;
        }
        int endIndex = title.offsetByCodePoints(0, GENERATED_TITLE_MAX_CODE_POINTS);
        return title.substring(0, endIndex);
    }

    // ==================== 流式对话 ====================

    /**
     * 流式对话 — SSE 版本
     *
     * <p>与同步版本流程相似，但实时返回事件流：</p>
     * <ol>
     *   <li>前置处理（验证会话、加载历史等）</li>
     *   <li>Phase 1: 规划（发送 plan 事件）</li>
     *   <li>Phase 2: 执行（发送 thinking/tool/observation 事件）</li>
     *   <li>后置处理（保存消息、记录 token）</li>
     * </ol>
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return SSE 事件流
     */
    @Override
    public Flux<SseEvent> chatStream(String sessionId, String userMessage) {
        return Flux.create(emitter -> {
            var sink = emitter;
            AtomicReference<String> streamAnswerRef = new AtomicReference<>("");
            AtomicReference<AgentTurnContext> contextRef = new AtomicReference<>();

            try {
                ConversationSession session = conversationService.getSession(sessionId);
                validateSessionOwnership(session);
                AgentTurnContext context = agentTurnContextService.prepare(session, userMessage, true);
                contextRef.set(context);
                String plan = agentPlannerService.plan(context, userMessage, "plan_stream", "【Stream 规划结果】", 200);
                if (plan != null) {
                    sink.next(SseEvent.plan(plan));
                }

                if (sink.isCancelled()) {
                    sink.next(SseEvent.interrupted("用户在规划后中断"));
                    sink.complete();
                    return;
                }

                ReactAgent reactAgent = reactAgentFactory.createForStream(context, plan);
                reactAgent.runStream(sessionId, userMessage, context.history())
                    .doOnNext(event -> {
                        if (!sink.isCancelled()) {
                            sink.next(event);

                            // 解析 token 用量并记录
                            if ("answer".equals(event.type())) {
                                Object content = event.data().get("content");
                                streamAnswerRef.set(content != null ? content.toString() : "");
                            }
                            if ("done".equals(event.type())) {
                                Object usage = event.data().get("tokenUsage");
                                if (usage != null && context.userId() != null) {
                                    try {
                                        @SuppressWarnings("unchecked")
                                        java.util.Map<String, Object> usageMap = (java.util.Map<String, Object>) usage;
                                        int promptTokens = ((Number) usageMap.get("promptTokens")).intValue();
                                        int completionTokens = ((Number) usageMap.get("completionTokens")).intValue();
                                        int totalTokens = ((Number) usageMap.get("totalTokens")).intValue();
                                        int cacheHitTokens = ((Number) usageMap.getOrDefault("cacheHitTokens", 0)).intValue();
                                        tokenUsageService.record(context.userId(), sessionId, promptTokens, completionTokens,
                                                cacheHitTokens, totalTokens, "executor", "chat");
                                        log.info("【Stream Token 用量】prompt={}, completion={}, cacheHit={}, total={}",
                                                promptTokens, completionTokens, cacheHitTokens, totalTokens);
                                    } catch (Exception e) {
                                        log.warn("解析 tokenUsage 失败: {}", e.getMessage());
                                    }
                                }
                                scheduleGeneratedTitle(sessionId, context.userId(), context.firstTurn(),
                                        userMessage, streamAnswerRef.get());
                            }
                        }
                    })
                    .doOnError(e -> {
                        log.error("ReactAgent Stream 失败", e);
                        if (!sink.isCancelled()) {
                            sink.next(SseEvent.error(e.getMessage()));
                            sink.complete();
                        }
                        agentToolContextService.clearRuntimeState(context.toolContext());
                    })
                    .doOnComplete(() -> {
                        agentToolContextService.clearRuntimeState(context.toolContext());
                        if (!sink.isCancelled()) {
                            sink.complete();
                        }
                    })
                    .subscribe();

            } catch (Exception e) {
                log.error("chatStream 失败", e);
                if (!sink.isCancelled()) {
                    sink.next(SseEvent.error(e.getMessage()));
                    sink.complete();
                }
                AgentTurnContext context = contextRef.get();
                if (context != null) {
                    agentToolContextService.clearRuntimeState(context.toolContext());
                }
            }
        });
    }

}
