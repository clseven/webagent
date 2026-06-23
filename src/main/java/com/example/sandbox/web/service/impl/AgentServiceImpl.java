package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.entity.*;
import com.example.sandbox.web.model.llm.AgentEventMapper;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.model.response.BatchDeleteSessionsResponse;
import com.example.sandbox.web.model.sse.SseEvent;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.AgentAppService;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.service.TokenUsageService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.enhance.KnowledgeEnhancer;
import com.example.sandbox.web.service.mcpclient.McpClientToolProvider;
import com.example.sandbox.web.service.mcpclient.RealMcpTool;
import com.example.sandbox.web.service.tool.WebSearchTool;
import com.example.sandbox.web.service.tool.KnowledgeSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
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

    /** 会话服务（管理消息历史、Session 生命周期） */
    @Autowired
    private ConversationServiceImpl conversationService;

    /** 规划 LLM（智谱 GLM，负责意图理解和任务规划） */
    @Autowired
    @Qualifier("plannerLlm")
    private LlmService plannerLlm;

    /** 执行 LLM（DeepSeek，负责工具调用和任务执行） */
    @Autowired
    @Qualifier("executorLlm")
    private LlmService executorLlm;

    /** 技能服务（加载可用技能列表） */
    @Autowired
    private SkillService skillService;

    /** Token 用量服务（记录每次 LLM 调用的 token 消耗） */
    @Autowired
    private TokenUsageService tokenUsageService;

    /** 所有可用工具（Spring 自动注入 Tool 接口的所有实现） */
    @Autowired
    private List<Tool> tools;

    /** MCP 动态工具提供器（基于官方 MCP Java SDK 的真 MCP 协议客户端） */
    @Autowired
    private McpClientToolProvider mcpToolProvider;

    /** Agent 应用服务（加载应用配置：知识库、技能过滤等） */
    @Autowired
    private AgentAppService agentAppService;

    /** 知识库服务（获取知识库描述，用于工具描述注入） */
    @Autowired
    private KnowledgeService knowledgeService;

    /** 知识库检索增强服务（Query Rewrite + Rerank） */
    @Autowired
    private KnowledgeEnhancer knowledgeEnhancer;

    /** 沙箱服务（懒加载，避免循环依赖） */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.example.sandbox.web.service.SandboxService sandboxService;

    /** MCP 动态工具开关，默认关闭 */
    @Value("${agent.mcp.enabled:false}")
    private boolean mcpEnabled;

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

        // 0. 确保沙箱已创建（异步可能还没完成）
        if (!sandboxService.hasSandbox(sessionId)) {
            log.info("Sandbox not ready for session {}, creating now...", sessionId);
            sandboxService.createSandbox(sessionId);
        }

        // 1. 加载当前会话的历史消息（存储前获取，不含本轮消息）
        List<ChatMessage> history = conversationService.getRecentHistory(sessionId, 20);
        log.info("【历史消息】会话: {} 条数: {}", sessionId, history.size());

        // 2. 存储用户消息
        conversationService.addUserMessage(sessionId, userMessage);
        log.info("【用户输入】会话: {} 内容: {}", sessionId, userMessage);

        // 3. 提取上传文件上下文
        String extraContext = extractFileContext(userMessage);

        // 4. 构建系统提示（仅技能元数据，不含消息历史 — 利用 prompt caching）
        String systemPrompt = conversationService.buildSystemPrompt(sessionId);
        if (extraContext != null) {
            systemPrompt = extraContext + "\n\n" + systemPrompt;
        }
        log.info("【系统提示】会话: {} 长度: {} 字符", sessionId, systemPrompt.length());

        // 4.5 加载 Agent 应用配置（知识库描述注入 + skill 过滤）
        Long appId = session.getAppId();
        AgentAppEntity app = null;
        if (appId != null) {
            try {
                app = agentAppService.getApp(appId);
            } catch (Exception e) {
                log.warn("加载 Agent 应用配置失败: appId={}", appId, e.getMessage());
            }
        }

        // 根据沙箱类型过滤工具
        boolean isAio = sandboxService.isAioSandbox(sessionId);
        String targetType = isAio ? "AIO" : "COMMON";
        List<Tool> filteredTools = new ArrayList<>(tools.stream()
                .filter(t -> {
                    String type = t.getDefinition().getSandboxType();
                    return "ALL".equals(type) || targetType.equals(type);
                })
                .toList());
        // MCP 动态工具：受配置开关控制，并与自定义工具去重（优先保留自定义工具）
        filteredTools = mergeMcpTools(filteredTools, sessionId, isAio);

        // WebSearch 工具：仅在前端开启搜索时才保留
        filteredTools = filterWebSearchTool(filteredTools);

        // 知识库描述注入：如果应用关联了知识库，动态修改 knowledge_search 工具的描述
        KnowledgeSearchTool knowledgeSearchTool = null;
        String kbDescription = null;
        if (app != null && !app.getKnowledgeBaseIds().isEmpty()) {
            // 构建知识库描述
            StringBuilder kbDescBuilder = new StringBuilder();
            kbDescBuilder.append("从知识库中检索相关信息。");
            for (Long kbId : app.getKnowledgeBaseIds()) {
                try {
                    String desc = knowledgeService.getKnowledgeBaseDescription(kbId);
                    if (desc != null && !desc.isEmpty()) {
                        kbDescBuilder.append(desc).append(" ");
                    }
                } catch (Exception e) {
                    log.warn("获取知识库描述失败: kbId={}", kbId);
                }
            }
            kbDescription = kbDescBuilder.toString();

            // 找到 KnowledgeSearchTool 并设置动态描述
            for (Tool t : filteredTools) {
                if (t instanceof KnowledgeSearchTool kst) {
                    knowledgeSearchTool = kst;
                    // 用应用关联的第一个知识库作为默认检索知识库
                    Long defaultKbId = app.getKnowledgeBaseIds().iterator().next();
                    kst.setCurrentKbId(defaultKbId);
                    break;
                }
            }

            log.info("【知识库注入】应用: {}, 知识库: {}, 描述: {}",
                    appId, app.getKnowledgeBaseIds(),
                    kbDescription.length() > 100 ? kbDescription.substring(0, 100) + "..." : kbDescription);
        }

        // 构建工具定义（如果有关联知识库，注入动态描述）
        final String finalKbDesc = kbDescription;
        final AgentAppEntity finalApp = app;
        List<ToolDefinition> toolDefinitions = filteredTools.stream()
                .map(t -> {
                    if (t instanceof KnowledgeSearchTool kst && finalApp != null && finalKbDesc != null) {
                        return kst.getDefinitionWithDescription(finalKbDesc);
                    }
                    return t.getDefinition();
                })
                .toList();
        log.info("【工具过滤】沙箱类型: {}, 可用工具: {}", targetType,
                filteredTools.stream().map(t -> t.getDefinition().getName()).toList());

        // 5. 知识库检索增强（Query Rewrite + Rerank）— 提前到规划前，PlanAgent 也需要知识库上下文
        Long userId = UserContext.getCurrentUserId();
        String enhancedContext = "";
        if (app != null && !app.getKnowledgeBaseIds().isEmpty()) {
            List<Long> kbIds = new ArrayList<>(app.getKnowledgeBaseIds());
            enhancedContext = knowledgeEnhancer.enhance(userId, kbIds, userMessage, history);
            if (!enhancedContext.isEmpty()) {
                systemPrompt = enhancedContext + "\n\n" + systemPrompt;
                log.info("【知识库增强】注入上下文: {} 字符", enhancedContext.length());
            }
        }

        // 6. Phase 1: PlanAgent 规划
        List<Skill> skills = skillService.listSkills();

        // Skill 过滤：如果应用配置了 skill，只使用应用关联的 skill
        if (app != null && !app.getSkillIds().isEmpty()) {
            Set<String> appSkillIds = app.getSkillIds();
            skills = skills.stream()
                    .filter(s -> appSkillIds.contains(s.getId()))
                    .toList();
            log.info("【Skill 过滤】应用: {}, 可用技能: {}", appId,
                    skills.stream().map(Skill::getId).toList());
        }

        String sessionContext = buildSessionContext(session, enhancedContext);
        PlanAgent planAgent = new PlanAgent(executorLlm, toolDefinitions, skills, sessionContext);
        PlanResult planResult = planAgent.plan(userMessage, history);
        String plan = planResult.getPlan();
        log.info("【规划结果】{}", plan.length() > 300 ? plan.substring(0, 300) + "..." : plan);

        // 保存规划阶段的 token 用量
        if (planResult.getTokenUsage() != null) {
            LlmUsage planUsage = planResult.getTokenUsage();
            tokenUsageService.record(userId, sessionId, planUsage.promptTokens(),
                    planUsage.completionTokens(), planUsage.cacheHitTokens(),
                    planUsage.totalTokens(), "planner", "plan");
        }

        // 7. Phase 2: ReactAgent 执行（历史消息作为固定前缀，利用 prompt caching）
        ReactAgent reactAgent = new ReactAgent(executorLlm, filteredTools, systemPrompt, plan);
        reactAgent.registerPreToolUseHook(AgentHookExamples.logHook());
        AgentResponse agentResponse = reactAgent.run(sessionId, userMessage, history);
        String response = agentResponse.getFinalAnswer();
        String reasoning = agentResponse.getFinalReasoning();
        List<Map<String, Object>> events = AgentEventMapper.fromPlanAndSteps(plan, agentResponse.getSteps());

        // 保存执行阶段的 token 用量
        LlmUsage execUsage = agentResponse.getTotalUsage();
        tokenUsageService.record(userId, sessionId, execUsage.promptTokens(),
                execUsage.completionTokens(), execUsage.cacheHitTokens(),
                execUsage.totalTokens(), "executor", "chat");

        // 7. 存储助手响应，并保存本轮可恢复展示的执行过程事件
        conversationService.addAssistantMessage(sessionId, response, reasoning, events);
        log.info("【助手输出】会话: {} 内容长度: {}, 思考链长度: {}",
                sessionId, response != null ? response.length() : 0, reasoning != null ? reasoning.length() : 0);

        // 清理 ThreadLocal
        if (knowledgeSearchTool != null) {
            knowledgeSearchTool.clearCurrentKbId();
            knowledgeSearchTool.clearDynamicDescription();
        }

        return ChatMessage.restore("assistant", response, reasoning, System.currentTimeMillis(), events);
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
     * 构建会话上下文（已启用技能描述 + 知识库增强上下文）
     */
    private String buildSessionContext(ConversationSession session, String enhancedContext) {
        StringBuilder sb = new StringBuilder();
        if (session.getEnabledSkillIds() != null && !session.getEnabledSkillIds().isEmpty()) {
            sb.append("## 已启用技能\n\n");
            for (String skillId : session.getEnabledSkillIds()) {
                try {
                    Skill skill = skillService.getSkill(skillId);
                    sb.append(skill.toMetadataLine()).append("\n");
                } catch (Exception e) {
                    log.warn("构建会话上下文时获取技能 {} 失败: {}", skillId, e.getMessage());
                    sb.append("- ").append(skillId).append("\n");
                }
            }
            sb.append("\n");
        }
        if (enhancedContext != null && !enhancedContext.isEmpty()) {
            sb.append(enhancedContext).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从用户消息中提取文件上传信息，生成上下文提示
     *
     * <p>检测用户消息中是否包含【上传的文件】段落，解析出文件名列表，
     * 生成提示告诉 LLM 文件已同步到沙箱的 /home/gem/uploads/ 目录。</p>
     */
    private String extractFileContext(String userMessage) {
        // 检测用户消息中是否提到【上传的文件】段落
        if (!userMessage.contains("【上传的文件】")) {
            return null;
        }

        // 提取文件名列表
        StringBuilder context = new StringBuilder();
        context.append("## 用户已上传的文件\n");
        context.append("文件已同步到沙盒 `/home/gem/uploads/` 目录，可直接读取：\n\n");

        // 用换行和 📎 标记来解析文件列表
        String[] lines = userMessage.split("\n");
        for (String line : lines) {
            if (line.contains("📎")) {
                // 提取文件名（📎 后面的内容，去掉大小信息）
                String filename = line.replace("📎", "").trim();
                int sizeIdx = filename.lastIndexOf(" (");
                if (sizeIdx > 0) {
                    filename = filename.substring(0, sizeIdx);
                }
                // 去掉可能的前缀符号
                filename = filename.replaceFirst("^[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "");
                if (!filename.isEmpty()) {
                    context.append("- ").append(filename).append("\n");
                }
            }
        }

        return context.toString();
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
            AtomicReference<KnowledgeSearchTool> knowledgeSearchToolRef = new AtomicReference<>();

            try {
                // 0. 前置处理
                ConversationSession session = conversationService.getSession(sessionId);
                validateSessionOwnership(session);

                if (!sandboxService.hasSandbox(sessionId)) {
                    log.info("Sandbox not ready for session {}, creating now...", sessionId);
                    sandboxService.createSandbox(sessionId);
                }

                List<ChatMessage> history = conversationService.getRecentHistory(sessionId, 20);
                log.info("【Stream 历史消息】会话: {} 条数: {}", sessionId, history.size());

                // 存储用户消息
                conversationService.addUserMessage(sessionId, userMessage);

                // 提取文件上下文
                String extraContext = extractFileContext(userMessage);

                // 构建系统提示
                String systemPrompt = conversationService.buildSystemPrompt(sessionId);
                if (extraContext != null) {
                    systemPrompt = extraContext + "\n\n" + systemPrompt;
                }

                // 加载应用配置
                Long appId = session.getAppId();
                AgentAppEntity app = null;
                if (appId != null) {
                    try {
                        app = agentAppService.getApp(appId);
                    } catch (Exception e) {
                        log.warn("加载 Agent 应用配置失败: appId={}", appId);
                    }
                }

                // 过滤工具
                boolean isAio = sandboxService.isAioSandbox(sessionId);
                String targetType = isAio ? "AIO" : "COMMON";
                List<Tool> filteredTools = new ArrayList<>(tools.stream()
                        .filter(t -> {
                            String type = t.getDefinition().getSandboxType();
                            return "ALL".equals(type) || targetType.equals(type);
                        })
                        .toList());
                // MCP 动态工具：受配置开关控制，并与自定义工具去重（优先保留自定义工具）
                filteredTools = mergeMcpTools(filteredTools, sessionId, isAio);

                // WebSearch 工具：仅在前端开启搜索时才保留
                filteredTools = filterWebSearchTool(filteredTools);

                // 知识库注入
                String kbDescription = null;
                if (app != null && !app.getKnowledgeBaseIds().isEmpty()) {
                    StringBuilder kbDescBuilder = new StringBuilder();
                    kbDescBuilder.append("从知识库中检索相关信息。");
                    for (Long kbId : app.getKnowledgeBaseIds()) {
                        try {
                            String desc = knowledgeService.getKnowledgeBaseDescription(kbId);
                            if (desc != null && !desc.isEmpty()) {
                                kbDescBuilder.append(desc).append(" ");
                            }
                        } catch (Exception e) {
                            log.warn("获取知识库描述失败: kbId={}", kbId);
                        }
                    }
                    kbDescription = kbDescBuilder.toString();

                    for (Tool t : filteredTools) {
                        if (t instanceof KnowledgeSearchTool kst) {
                            knowledgeSearchToolRef.set(kst);
                            Long defaultKbId = app.getKnowledgeBaseIds().iterator().next();
                            kst.setCurrentKbId(defaultKbId);
                            break;
                        }
                    }
                }

                final String finalKbDesc = kbDescription;
                final AgentAppEntity finalApp = app;
                List<ToolDefinition> toolDefinitions = filteredTools.stream()
                        .map(t -> {
                            if (t instanceof KnowledgeSearchTool kst && finalApp != null && finalKbDesc != null) {
                                return kst.getDefinitionWithDescription(finalKbDesc);
                            }
                            return t.getDefinition();
                        })
                        .toList();

                log.info("【Stream 工具过滤】沙箱类型: {}, 可用工具: {}", targetType,
                        filteredTools.stream().map(t -> t.getDefinition().getName()).toList());

                // 知识库检索增强（Query Rewrite + Rerank）— 提前到规划前，PlanAgent 也需要知识库上下文
                Long userId = UserContext.getCurrentUserId();
                String enhancedContext = "";
                if (app != null && !app.getKnowledgeBaseIds().isEmpty()) {
                    List<Long> kbIds = new ArrayList<>(app.getKnowledgeBaseIds());
                    enhancedContext = knowledgeEnhancer.enhance(userId, kbIds, userMessage, history);
                    if (!enhancedContext.isEmpty()) {
                        systemPrompt = enhancedContext + "\n\n" + systemPrompt;
                        log.info("【Stream 知识库增强】注入上下文: {} 字符", enhancedContext.length());
                    }
                }

                // Phase 1: 规划
                List<Skill> skills = skillService.listSkills();

                if (app != null && !app.getSkillIds().isEmpty()) {
                    Set<String> appSkillIds = app.getSkillIds();
                    skills = skills.stream()
                            .filter(s -> appSkillIds.contains(s.getId()))
                            .toList();
                }

                String sessionContext = buildSessionContext(session, enhancedContext);
                PlanAgent planAgent = new PlanAgent(executorLlm, toolDefinitions, skills, sessionContext);
                PlanResult planResult = planAgent.plan(userMessage, history);
                String plan = planResult.getPlan();

                // 发送规划事件
                sink.next(SseEvent.plan(plan));
                log.info("【Stream 规划结果】{}", plan.length() > 200 ? plan.substring(0, 200) + "..." : plan);

                // 记录规划 token
                if (planResult.getTokenUsage() != null) {
                    LlmUsage planUsage = planResult.getTokenUsage();
                    tokenUsageService.record(userId, sessionId, planUsage.promptTokens(),
                            planUsage.completionTokens(), planUsage.cacheHitTokens(),
                            planUsage.totalTokens(), "planner", "plan_stream");
                }

                // 检查是否中断
                if (sink.isCancelled()) {
                    sink.next(SseEvent.interrupted("用户在规划后中断"));
                    sink.complete();
                    return;
                }

                // Phase 2: 执行
                ReactAgent reactAgent = new ReactAgent(executorLlm, filteredTools, systemPrompt, plan,
                        conversationService, sessionId);
                reactAgent.registerPreToolUseHook(AgentHookExamples.logHook());
                reactAgent.registerPostToolUseHook(AgentHookExamples.largeOutputHook());

                // 用于累积 token 用量
                AtomicReference<LlmUsage> totalUsage = new AtomicReference<>();

                reactAgent.runStream(sessionId, userMessage, history)
                    .doOnNext(event -> {
                        if (!sink.isCancelled()) {
                            sink.next(event);

                            // 解析 token 用量并记录
                            if ("done".equals(event.type())) {
                                Object usage = event.data().get("tokenUsage");
                                if (usage != null && userId != null) {
                                    try {
                                        @SuppressWarnings("unchecked")
                                        java.util.Map<String, Object> usageMap = (java.util.Map<String, Object>) usage;
                                        int promptTokens = ((Number) usageMap.get("promptTokens")).intValue();
                                        int completionTokens = ((Number) usageMap.get("completionTokens")).intValue();
                                        int totalTokens = ((Number) usageMap.get("totalTokens")).intValue();
                                        int cacheHitTokens = ((Number) usageMap.getOrDefault("cacheHitTokens", 0)).intValue();
                                        tokenUsageService.record(userId, sessionId, promptTokens, completionTokens,
                                                cacheHitTokens, totalTokens, "executor", "chat");
                                        log.info("【Stream Token 用量】prompt={}, completion={}, cacheHit={}, total={}",
                                                promptTokens, completionTokens, cacheHitTokens, totalTokens);
                                    } catch (Exception e) {
                                        log.warn("解析 tokenUsage 失败: {}", e.getMessage());
                                    }
                                }
                            }
                        }
                    })
                    .doOnError(e -> {
                        log.error("ReactAgent Stream 失败", e);
                        if (!sink.isCancelled()) {
                            sink.next(SseEvent.error(e.getMessage()));
                            sink.complete();
                        }
                    })
                    .doOnComplete(() -> {
                        // 清理 ThreadLocal（消息保存已在 ReactAgent 内部处理）
                        KnowledgeSearchTool kst = knowledgeSearchToolRef.get();
                        if (kst != null) {
                            kst.clearCurrentKbId();
                            kst.clearDynamicDescription();
                        }

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

                // 清理
                KnowledgeSearchTool kst = knowledgeSearchToolRef.get();
                if (kst != null) {
                    kst.clearCurrentKbId();
                    kst.clearDynamicDescription();
                }
            }
        });
    }

    /**
     * 按配置合并 MCP 动态工具。
     *
     * <p>受 {@code agent.mcp.enabled} 控制，默认关闭。
     * 开启时与自定义工具去重：若 MCP 工具原始名与已有自定义工具同名，保留自定义工具。</p>
     *
     * <p>新版 MCP 客户端是基于官方 SDK 的真 MCP 协议长连接，与具体沙箱类型无关，
     * 因此不再像旧版那样限制只在 AIO 沙箱启用。</p>
     *
     * @param customTools 已过滤的自定义工具列表
     * @param sessionId   当前会话 ID
     * @param isAio       是否为 AIO 沙箱（保留参数兼容现有调用，目前未使用）
     * @return 合并后的工具列表（可能包含 MCP 工具）
     */
    private List<Tool> mergeMcpTools(List<Tool> customTools, String sessionId, boolean isAio) {
        if (!mcpEnabled) {
            return customTools;
        }

        // 收集自定义工具名，用于去重
        Set<String> customNames = customTools.stream()
                .map(t -> t.getDefinition().getName())
                .collect(Collectors.toSet());

        List<Tool> result = new ArrayList<>(customTools);
        int skipped = 0;
        for (Tool mcpTool : mcpToolProvider.getTools(sessionId)) {
            if (mcpTool instanceof RealMcpTool mcp) {
                String originalName = mcp.getOriginalName();
                if (customNames.contains(originalName)) {
                    log.debug("MCP 工具 {} (原始名: {}) 与自定义工具冲突，使用自定义版本",
                            mcpTool.getDefinition().getName(), originalName);
                    skipped++;
                    continue;
                }
            }
            result.add(mcpTool);
        }

        if (skipped > 0) {
            log.info("MCP 工具去重: 跳过 {} 个冲突工具，保留 {} 个 MCP 工具",
                    skipped, result.size() - customTools.size());
        }
        return result;
    }

    /**
     * 按前端网络搜索开关过滤 {@link WebSearchTool}。
     *
     * <p>开关来自当前请求的 {@code searchEnabled} 参数（通过 {@link com.example.sandbox.web.context.UserContext} 传递）。
     * 关闭时从工具列表中移除 web_search，LLM 将无法调用。</p>
     */
    private List<Tool> filterWebSearchTool(List<Tool> tools) {
        boolean enabled = UserContext.isWebSearchEnabled();
        if (enabled) {
            log.debug("网络搜索已启用");
            return tools;
        }
        List<Tool> filtered = tools.stream()
                .filter(t -> !(t instanceof WebSearchTool))
                .toList();
        if (filtered.size() < tools.size()) {
            log.debug("网络搜索已关闭，web_search 工具已移除");
        }
        return filtered;
    }
}
