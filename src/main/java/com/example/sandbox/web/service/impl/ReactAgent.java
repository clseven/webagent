package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.AgentEventMapper;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.AgentStep;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.model.llm.LlmToolResult;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.model.sse.SseEvent;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct Agent 实现 — 通过"推理 + 行动"循环完成复杂任务。
 *
 * <p>ReAct = Reasoning + Acting，核心思想是让 LLM 在行动前先思考，根据结果再调整策略。</p>
 *
 * <h3>工作流程</h3>
 * <pre>
 * while (未完成 && 迭代次数 < 25) {
 *     1. LLM 思考：分析当前情况，决定下一步
 *     2. LLM 决策：调用工具 OR 直接回答
 *     3. 如果调工具 → 执行 → 把结果告诉 LLM → 继续循环
 *     4. 如果回答 → 返回结果
 * }
 * </pre>
 *
 * <h3>消息累积机制</h3>
 * <p>每轮执行结果都会追加到 messages，LLM 能看到完整历史，包括：</p>
 * <ul>
 *   <li>之前调了什么工具、参数是什么</li>
 *   <li>执行结果是成功还是失败</li>
 *   <li>失败原因是什么</li>
 * </ul>
 * <p>这样 LLM 能根据失败信息自动调整策略，实现"自我纠错"。</p>
 *
 * @author example
 * @date 2026/05/15
 */
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    /** 最大迭代次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 25;

    /** 触发历史消息压缩的 token 阈值（字符数估算） */
    private static final int SUMMARIZE_THRESHOLD = 24_000;

    /** 字符到 token 的估算比例（中文约 1.5-2 字符/token，取保守值 3） */
    private static final int TOKEN_CHARS_RATIO = 3;

    /** 单个工具执行的超时时间 */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(120);

    /** 用户中断标记（固定文本，有利于缓存命中） */
    private static final String INTERRUPTED_MARKER = "【用户手动暂停】任务被中断。";

    /** 压缩摘要消息前缀，用于识别由上下文压缩生成的 user 消息。 */
    private static final String COMPACTED_SUMMARY_PREFIX = "[Compacted conversation summary]\n\n";

    /** 摘要输入中单条长内容保留的头部字符数。 */
    private static final int SUMMARY_CONTENT_HEAD_CHARS = 350;

    /** 摘要输入中单条长内容保留的尾部字符数。 */
    private static final int SUMMARY_CONTENT_TAIL_CHARS = 350;

    private static final String SUMMARIZE_PROMPT = """
            请用中文将以下对话历史压缩为一段可继续执行任务的上下文摘要（不超过 500 字）。

            CRITICAL: 只输出摘要正文。绝对不要调用工具，不要输出分析过程。

            必须保留：
            - 用户当前请求、目标和关键约束
            - 已完成的重要操作、观察结果和结论
            - 已读取或修改过的文件、路径、配置和命令
            - 工具调用产生的关键结果，尤其是仍会影响下一步的结果
            - 未完成事项、阻塞点和下一步建议
            - 用户明确表达的偏好、禁止事项和项目规则

            如果已有摘要，请合并更新，不要重复堆叠。
            如果信息不确定，请标记为“未确认”，不要编造。

            %s

            对话历史：
            %s""";

    /** LLM 服务实例（执行器模型，如 DeepSeek） */
    private final LlmService llmService;

    /** 对话服务（用于保存消息到 DB） */
    private final ConversationService conversationService;

    /** 会话 ID（用于保存消息） */
    private final String sessionId;

    /** 可用工具映射：工具名 → 工具实例 */
    private final Map<String, Tool> tools;

    /** 工具定义列表（传给 LLM，让它知道有哪些工具可用） */
    private final List<ToolDefinition> toolDefinitions;

    /** 系统提示词（包含工具说明、技能指导、执行计划等） */
    private final String systemPrompt;

    /** 规划阶段产出的执行计划（注入到 system prompt 中指导执行） */
    private final String plan;

    /** 对话摘要（当历史消息超出 token 预算时生成），实际作为 user 消息保留在 messages 中 */
    private String conversationSummary;

    // ==================== Hook 系统 ====================

    /**
     * 工具执行前 Hook：接收工具调用和会话 ID，返回 null 表示放行，返回非 null 字符串表示阻止执行（原因作为工具结果返回给 LLM）。
     */
    @FunctionalInterface
    public interface PreToolUseHook {
        String run(LlmToolCall toolCall, String sessionId, Map<String, Tool> tools);
    }

    /**
     * 工具执行后 Hook：接收工具调用、执行结果和会话 ID。
     *
     * <p>返回 null 表示无额外消息；返回非 null 的 {@link ChatMessage} 则会被追加到对话历史，
     * 在下一轮 LLM 调用时可见。适用于需要向 LLM 注入额外上下文的场景，例如图片数据注入。</p>
     */
    @FunctionalInterface
    public interface PostToolUseHook {
        ChatMessage run(LlmToolCall toolCall, String result, String sessionId);
    }

    /**
     * 停止前 Hook：接收当前消息列表，返回 null 表示允许退出，返回非 null 字符串表示强制继续（作为新 user 消息注入）。
     */
    @FunctionalInterface
    public interface StopHook {
        String run(List<ChatMessage> messages);
    }

    /** 后台任务管理器（为 null 时后台功能不启用，如流式路径） */
    private final BackgroundTaskManager backgroundTaskManager;

    /** Hook 注册表：事件名 → 回调列表 */
    private final Map<String, List<Object>> hooks = new ConcurrentHashMap<>();

    /**
     * 注册一个 PreToolUse Hook。
     */
    public ReactAgent registerPreToolUseHook(PreToolUseHook hook) {
        hooks.computeIfAbsent("PreToolUse", k -> new ArrayList<>()).add(hook);
        return this;
    }

    /**
     * 注册一个 PostToolUse Hook。
     */
    public ReactAgent registerPostToolUseHook(PostToolUseHook hook) {
        hooks.computeIfAbsent("PostToolUse", k -> new ArrayList<>()).add(hook);
        return this;
    }

    /**
     * 注册一个 Stop Hook。
     */
    public ReactAgent registerStopHook(StopHook hook) {
        hooks.computeIfAbsent("Stop", k -> new ArrayList<>()).add(hook);
        return this;
    }

    /**
     * 触发 PreToolUse 事件：遍历所有注册的回调，任一返回非 null 即阻止执行。
     *
     * @return null = 放行，非 null = 阻止原因
     */
    @SuppressWarnings("unchecked")
    private String triggerPreToolUseHooks(LlmToolCall toolCall, String sessionId) {
        List<Object> callbacks = hooks.get("PreToolUse");
        if (callbacks == null) return null;
        for (Object cb : callbacks) {
            String result = ((PreToolUseHook) cb).run(toolCall, sessionId, tools);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * 触发 PostToolUse 事件：遍历所有注册的回调。
     *
     * <p>任一回调返回非 null 的 {@link ChatMessage} 时立即返回该消息（短路）；
     * 全部返回 null 则返回 null 表示无需注入额外消息。</p>
     */
    @SuppressWarnings("unchecked")
    private ChatMessage triggerPostToolUseHooks(LlmToolCall toolCall, String result, String sessionId) {
        List<Object> callbacks = hooks.get("PostToolUse");
        if (callbacks == null) return null;
        for (Object cb : callbacks) {
            ChatMessage extra = ((PostToolUseHook) cb).run(toolCall, result, sessionId);
            if (extra != null) return extra;
        }
        return null;
    }

    /**
     * 触发 Stop 事件：遍历所有注册的回调，任一返回非 null 即阻止退出（返回值作为新的 user 消息注入）。
     *
     * @return null = 允许退出，非 null = 强制继续
     */
    @SuppressWarnings("unchecked")
    private String triggerStopHooks(List<ChatMessage> messages) {
        List<Object> callbacks = hooks.get("Stop");
        if (callbacks == null) return null;
        for (Object cb : callbacks) {
            String result = ((StopHook) cb).run(messages);
            if (result != null) return result;
        }
        return null;
    }

    // ==================== 子代理支持 ====================

    /**
     * 创建一个子 Agent 实例，继承父的 Hook 和安全策略，但拥有独立的消息上下文。
     *
     * <h3>子 Agent 特点</h3>
     * <ul>
     *   <li><b>独立消息列表</b>：不共享父的 {@code messages}，上下文隔离</li>
     *   <li><b>受限工具列表</b>：只能使用调用方传入的工具</li>
     *   <li><b>不保存到主会话</b>：{@code conversationService=null}，子代理内部消息不写入 DB</li>
     *   <li><b>继承 PreToolUse/PostToolUse Hook</b>：安全策略不跳过</li>
     *   <li><b>不继承 Stop Hook</b>：子 Agent 完成后直接返回，不需要外部干预</li>
     * </ul>
     *
     * <h3>教学材料对应</h3>
     * <p>对应 s06 Subagent 的 {@code spawn_subagent(description)} 函数：
     * 子 Agent 拥有全新的 {@code messages[]}，跑自己的循环，完成后只回传结论。</p>
     *
     * @param restrictedTools 子 Agent 可用的工具（应不包含 run_subagent）
     * @param subagentPrompt  子 Agent 专属系统提示词
     * @return 新的 ReactAgent 实例，SessionId 与父相同（访问同一个沙箱）
     */
    public ReactAgent fork(List<Tool> restrictedTools, String subagentPrompt) {
        ReactAgent child = new ReactAgent(
                this.llmService, restrictedTools, subagentPrompt,
                null,   // 无执行计划 — 子 Agent 直接执行任务
                null,   // conversationService=null — 不写主会话
                this.sessionId,  // 保留 sessionId — 需要访问同一个沙箱
                null    // 子代理不启用后台任务
        );

        // 继承 PreToolUse Hook（安全检查不跳过）
        List<Object> preHooks = hooks.get("PreToolUse");
        if (preHooks != null) {
            for (Object hook : preHooks) {
                child.registerPreToolUseHook((PreToolUseHook) hook);
            }
        }

        // 继承 PostToolUse Hook（副作用型）
        List<Object> postHooks = hooks.get("PostToolUse");
        if (postHooks != null) {
            for (Object hook : postHooks) {
                child.registerPostToolUseHook((PostToolUseHook) hook);
            }
        }

        // 不继承 Stop Hook — 子 Agent 完成后直接返回

        log.debug("Fork 子 Agent: tools={}, prompt={} 字符",
                restrictedTools.size(),
                subagentPrompt != null ? subagentPrompt.length() : 0);
        return child;
    }

    /**
     * 获取当前 Agent 的完整工具列表（子代理需要此方法按名称过滤工具）。
     *
     * @return 工具实例列表（不可修改的副本）
     */
    public List<Tool> getTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 创建 ReactAgent（无技能提示、无执行计划）
     *
     * @param llmService LLM 服务实例
     * @param toolList   可用工具列表
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList) {
        this(llmService, toolList, null, null, null, null, null);
    }

    /**
     * 创建 ReactAgent（带技能提示，无执行计划）
     *
     * @param llmService  LLM 服务实例
     * @param toolList    可用工具列表
     * @param skillPrompt 技能指导提示词（从技能文件加载）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt) {
        this(llmService, toolList, skillPrompt, null, null, null, null);
    }

    /**
     * 创建 ReactAgent（完整构造函数）
     *
     * @param llmService  LLM 服务实例
     * @param toolList    可用工具列表
     * @param skillPrompt 技能指导提示词（可为 null）
     * @param plan        执行计划（由 PlanAgent 产出，可为 null）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt, String plan) {
        this(llmService, toolList, skillPrompt, plan, null, null, null);
    }

    /**
     * 创建 ReactAgent（带对话服务，无后台任务 — 流式路径用）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt, String plan,
                      ConversationService conversationService, String sessionId) {
        this(llmService, toolList, skillPrompt, plan, conversationService, sessionId, null);
    }

    /**
     * 创建 ReactAgent（带对话服务，支持流式保存消息）
     *
     * @param llmService          LLM 服务实例
     * @param toolList            可用工具列表
     * @param skillPrompt         技能指导提示词（可为 null）
     * @param plan                执行计划（由 PlanAgent 产出，可为 null）
     * @param conversationService 对话服务（可为 null，为 null 时不保存消息）
     * @param sessionId           会话 ID（用于保存消息）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt, String plan,
                      ConversationService conversationService, String sessionId,
                      BackgroundTaskManager backgroundTaskManager) {
        this.llmService = llmService;
        this.conversationService = conversationService;
        this.sessionId = sessionId;
        this.backgroundTaskManager = backgroundTaskManager;
        this.tools = new ConcurrentHashMap<>();
        this.toolDefinitions = new ArrayList<>();
        this.plan = plan;

        for (Tool tool : toolList) {
            tools.put(tool.getDefinition().getName(), tool);
            toolDefinitions.add(tool.getDefinition());
        }

        this.systemPrompt = buildSystemPrompt(skillPrompt);
    }

    /**
     * 执行 ReAct 循环 — 核心执行逻辑
     *
     * <p>循环流程：</p>
     * <ol>
     *   <li>调用 LLM，让它分析当前情况并决定下一步</li>
     *   <li>如果 LLM 返回工具调用 → 执行工具 → 结果追加到消息 → 继续循环</li>
     *   <li>如果 LLM 返回最终答案 → 结束循环，返回结果</li>
     *   <li>如果达到最大迭代次数 → 返回失败提示</li>
     * </ol>
     *
     * <p>消息累积：每轮的 Thought/Action/Observation 都追加到 messages，
     * LLM 能看到完整历史，实现自我纠错。</p>
     *
     * @param sessionId   会话 ID（用于工具执行时获取沙箱客户端）
     * @param userMessage 用户消息（作为本轮第一条 user 消息）
     * @param history     历史消息（不含当前用户消息），作为固定前缀以利用 prompt caching
     * @return 执行结果（包含响应、迭代链和 token 用量）
     */
    public AgentResponse run(String sessionId, String userMessage, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.addAll(trimHistory(history));
        messages.add(ChatMessage.userMessage(userMessage));

        log.info("ReAct 消息构建完成，历史 {} 条（截取后 {} 条），当前消息 1 条",
                history.size(), messages.size() - 1);

        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalCacheHitTokens = 0;
        int totalTokens = 0;

        List<AgentStep> steps = new ArrayList<>();

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 注入后台任务完成通知
            if (backgroundTaskManager != null) {
                String notification = backgroundTaskManager.collect(sessionId);
                if (notification != null) {
                    messages.add(ChatMessage.userMessage(notification));
                    log.debug("注入后台通知: {} 字符", notification.length());
                }
            }

            // 每次 LLM 调用前检查 token 预算，超出则压缩旧消息为摘要
            compressIfNeeded(messages);

            String prompt = effectiveSystemPrompt();
            LlmResponse response = llmService.chatWithTools(prompt, messages, toolDefinitions);

            // 累积 token 用量
            if (response.getTokenUsage() != null) {
                LlmUsage usage = response.getTokenUsage();
                totalPromptTokens += usage.promptTokens();
                totalCompletionTokens += usage.completionTokens();
                totalCacheHitTokens += usage.cacheHitTokens();
                totalTokens += usage.totalTokens();
            }

            if (response.isFinished()) {
                // 🪝 Stop Hook：任一回调返回非 null 即注入消息并强制继续
                String forceContinue = triggerStopHooks(messages);
                if (forceContinue != null) {
                    log.info("Stop Hook 强制继续: {}", forceContinue.length() > 100 ? forceContinue.substring(0, 100) + "..." : forceContinue);
                    messages.add(ChatMessage.userMessage(forceContinue));
                    continue;
                }

                // 最终返回前等待遗留后台任务
                if (backgroundTaskManager != null && backgroundTaskManager.isPending(sessionId)) {
                    log.info("最终返回前等待后台任务...");
                    String remaining = backgroundTaskManager.awaitPending(sessionId, 30_000);
                    if (remaining != null) {
                        messages.add(ChatMessage.userMessage(remaining));
                        // 消化循环：LLM 看到通知后可能又调工具，最多 6 轮
                        for (int digest = 0; digest < 6; digest++) {
                            LlmResponse digestResponse = llmService.chatWithTools(
                                    prompt, messages, toolDefinitions);
                            if (digestResponse.getTokenUsage() != null) {
                                LlmUsage usage = digestResponse.getTokenUsage();
                                totalPromptTokens += usage.promptTokens();
                                totalCompletionTokens += usage.completionTokens();
                                totalCacheHitTokens += usage.cacheHitTokens();
                                totalTokens += usage.totalTokens();
                            }
                            if (digestResponse.isFinished()) {
                                response = digestResponse;
                                break;
                            }
                            if (digestResponse.hasToolCall()) {
                                LlmToolCall tc = digestResponse.getToolCall();
                                String obs = executeTool(sessionId, tc.name(), tc.arguments());
                                LlmToolCall historyTc = ensureToolCallId(tc);
                                messages.add(ChatMessage.assistantToolCallMessage(historyTc));
                                messages.add(ChatMessage.toolMessage(historyTc.id(), obs));
                                steps.add(new AgentStep(iteration, digestResponse.getContent(),
                                        digestResponse.getReasoningContent(), historyTc,
                                        LlmToolResult.success(historyTc.id(), tc.name(), obs, 0),
                                        digestResponse.getTokenUsage()));
                            }
                        }
                    }
                }

                log.info("ReAct 完成，共 {} 次迭代，token: {}", iteration, totalTokens);
                LlmUsage totalUsage = new LlmUsage(totalPromptTokens, totalCompletionTokens, totalTokens, totalCacheHitTokens);
                return new AgentResponse(response.getContent(), response.getReasoningContent(), steps, totalUsage, iteration);
            }

            if (response.hasToolCall()) {
                LlmToolCall toolCall = response.getToolCall();
                String toolName = toolCall.name();
                Map<String, Object> arguments = toolCall.arguments();

                String llmContent = response.getContent();
                if (llmContent != null && !llmContent.isEmpty()) {
                    log.debug("LLM 思考: {}", llmContent.length() > 500 ? llmContent.substring(0, 500) + "..." : llmContent);
                }

                // 执行工具并记录耗时
                log.info("执行工具: {} 参数: {}", toolName, arguments);
                String displayReason = AgentActionNarrator.describe(toolName, arguments, plan);
                long startTime = System.currentTimeMillis();

                // 🪝 PreToolUse Hook：任一回调返回非 null 即阻止执行
                String blocked = triggerPreToolUseHooks(toolCall, sessionId);
                String observation;
                if (blocked != null) {
                    log.info("工具 {} 被 Hook 阻止: {}", toolName, blocked);
                    observation = blocked;
                } else {
                    observation = executeTool(sessionId, toolName, arguments);
                }

                long durationMs = System.currentTimeMillis() - startTime;

                // 🪝 PostToolUse Hook：仅在实际执行时触发
                ChatMessage hookInjection = null;
                if (blocked == null) {
                    hookInjection = triggerPostToolUseHooks(toolCall, observation, sessionId);
                }

                log.debug("工具结果: {}", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

                LlmToolCall historyToolCall = ensureToolCallId(toolCall);

                // 记录本轮步骤
                LlmToolResult toolResult = LlmToolResult.success(
                        historyToolCall.id(), toolName, observation, durationMs, displayReason);
                AgentStep step = new AgentStep(iteration, llmContent, response.getReasoningContent(),
                        historyToolCall, toolResult, response.getTokenUsage());
                steps.add(step);

                // 追加到消息历史（原生 tool calling 协议）
                messages.add(ChatMessage.assistantToolCallMessage(historyToolCall));
                messages.add(ChatMessage.toolMessage(historyToolCall.id(), observation));

                // Hook 注入额外消息（如图片数据）
                if (hookInjection != null) {
                    messages.add(hookInjection);
                    log.debug("PostToolUse Hook 注入消息: role={}", hookInjection.getRole());
                }
            } else {
                String finalContent = response.getContent();
                if (finalContent != null && !finalContent.isEmpty()) {
                    log.debug("LLM 最终回复长度: {}", finalContent.length());
                }
            }
        }

        log.warn("ReAct 达到最大迭代次数 ({})，会话 {}", MAX_ITERATIONS, sessionId);
        LlmUsage totalUsage = new LlmUsage(totalPromptTokens, totalCompletionTokens, totalTokens, totalCacheHitTokens);
        return new AgentResponse(
                "抱歉，我尝试了多次但仍未能完成任务。请尝试简化您的要求或提供更多信息。",
                null, steps, totalUsage, iteration);
    }

    private LlmToolCall ensureToolCallId(LlmToolCall toolCall) {
        if (toolCall.id() != null && !toolCall.id().isBlank()) {
            return toolCall;
        }
        return new LlmToolCall(
                "fallback_" + java.util.UUID.randomUUID(),
                toolCall.name(),
                toolCall.arguments());
    }

    /**
     * 构建有效的系统提示词。
     *
     * <p>对话摘要属于会话历史，不混入 system prompt，避免上游模型认为请求中缺少 user query。</p>
     */
    private String effectiveSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 如果消息总 token 超过阈值，把最旧的消息压缩为摘要。
     *
     * <p>保留最近 ~40% 的消息作为原始上下文，被压缩的消息从数组中移除，
     * 生成的摘要作为一条 user 消息放回 messages，确保上游请求仍包含用户查询。</p>
     */
    private void compressIfNeeded(List<ChatMessage> messages) {
        int totalTokens = estimateTokens(messages);
        if (totalTokens <= SUMMARIZE_THRESHOLD) {
            return;
        }

        // 找到 60% token 位置，之前的消息将被压缩
        int threshold = 0;
        int splitAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            threshold += messageContentForContext(messages.get(i)).length() / TOKEN_CHARS_RATIO;
            if (threshold > totalTokens * 0.6) {
                splitAt = i;
                break;
            }
        }

        int alignedSplitAt = alignSplitAtToToolCallBoundary(messages, splitAt);
        if (alignedSplitAt != splitAt) {
            log.debug("压缩切分点从 {} 回退到 {}，以保留完整的工具调用消息组",
                    splitAt, alignedSplitAt);
        }
        splitAt = alignedSplitAt;

        if (splitAt <= 2) {
            return; // 太少消息不值得压缩
        }

        List<ChatMessage> oldMessages = new ArrayList<>(messages.subList(0, splitAt));
        messages.subList(0, splitAt).clear();

        String newSummary = summarizeMessages(oldMessages);
        conversationSummary = newSummary;
        messages.add(0, ChatMessage.userMessage(COMPACTED_SUMMARY_PREFIX + newSummary));

        log.info("压缩 {} 条旧消息为摘要 ({} 字符)，剩余 {} 条",
                oldMessages.size(), newSummary.length(), messages.size());
    }

    /**
     * 调用 LLM 生成摘要，压缩旧消息
     */
    private String summarizeMessages(List<ChatMessage> oldMessages) {
        StringBuilder history = new StringBuilder();
        int messageIndex = 1;
        for (ChatMessage msg : oldMessages) {
            String structuredMessage = structuredMessageForSummary(messageIndex, msg);
            if (structuredMessage.isEmpty()) {
                continue;
            }
            history.append(structuredMessage).append("\n");
            messageIndex++;
        }

        String existingNote = conversationSummary != null
                ? "已有摘要（请合并更新）：\n" + conversationSummary
                : "（首次摘要）";

        String prompt = String.format(SUMMARIZE_PROMPT, existingNote, history);

        try {
            String summary = llmService.chat(List.of(ChatMessage.userMessage(prompt)));
            return summary != null ? summary : history.toString();
        } catch (Exception e) {
            log.warn("摘要生成失败，保留原始文本", e);
            return history.toString();
        }
    }

    /**
     * 将单条消息转换为给摘要模型读取的强结构文本。
     *
     * <p>结构中显式保留序号、角色、工具调用 ID、工具名称和参数，避免摘要模型只靠自然语言顺序猜测消息边界。</p>
     *
     * @param index   消息在本次摘要输入中的序号
     * @param message 待格式化的对话消息
     * @return 结构化文本；如果消息是旧压缩摘要且应跳过，则返回空字符串
     */
    private String structuredMessageForSummary(int index, ChatMessage message) {
        String content = message.getContent();
        if ("user".equals(message.getRole()) && content != null && content.startsWith(COMPACTED_SUMMARY_PREFIX)) {
            return "";
        }

        StringBuilder entry = new StringBuilder();
        entry.append("[").append(index).append("]\n");
        entry.append("role: ").append(message.getRole()).append("\n");

        if (message.getToolCallId() != null && !message.getToolCallId().isBlank()) {
            entry.append("tool_call_id: ").append(message.getToolCallId()).append("\n");
        }

        if (!message.getToolCalls().isEmpty()) {
            entry.append("tool_calls:\n");
            for (LlmToolCall toolCall : message.getToolCalls()) {
                entry.append("  - id: ").append(valueOrUnknown(toolCall.id())).append("\n");
                entry.append("    name: ").append(valueOrUnknown(toolCall.name())).append("\n");
                entry.append("    arguments: ")
                        .append(compactSummaryContent(String.valueOf(toolCall.arguments())))
                        .append("\n");
            }
        }

        String summaryContent = summaryContent(message);
        if (!summaryContent.isEmpty()) {
            entry.append("content:\n");
            entry.append(indentSummaryBlock(compactSummaryContent(summaryContent))).append("\n");
        }

        return entry.toString();
    }

    /**
     * 提取单条消息在摘要输入中的正文。
     *
     * <p>工具调用消息的结构字段由 {@link #structuredMessageForSummary(int, ChatMessage)} 单独输出；
     * 这里主要处理普通 content、任务通知过滤和多模态内容提示。</p>
     *
     * @param message 待提取正文的消息
     * @return 可放入摘要输入的正文，可能为空字符串
     */
    private String summaryContent(ChatMessage message) {
        if (message.getContent() != null) {
            if (message.getContent().startsWith("<task_notification>")) {
                return "";
            }
            return message.getContent();
        }
        if (message.getContentParts() != null && !message.getContentParts().isEmpty()) {
            return "[多模态内容块数量: " + message.getContentParts().size() + "]";
        }
        return "";
    }

    /**
     * 压缩单条摘要输入内容，长文本保留头尾并标明中间省略长度。
     *
     * @param content 原始内容
     * @return 适合放入摘要提示词的短内容
     */
    private String compactSummaryContent(String content) {
        if (content == null) {
            return "";
        }
        int maxLength = SUMMARY_CONTENT_HEAD_CHARS + SUMMARY_CONTENT_TAIL_CHARS;
        if (content.length() <= maxLength) {
            return content;
        }
        int omitted = content.length() - maxLength;
        return content.substring(0, SUMMARY_CONTENT_HEAD_CHARS)
                + "\n...[中间省略 " + omitted + " 字符]...\n"
                + content.substring(content.length() - SUMMARY_CONTENT_TAIL_CHARS);
    }

    /**
     * 为多行摘要正文增加缩进，使结构化消息更容易被模型区分字段和值。
     *
     * @param content 待缩进内容
     * @return 每行前缀两个空格的内容
     */
    private String indentSummaryBlock(String content) {
        return "  " + content.replace("\n", "\n  ");
    }

    /**
     * 将空白技术标识符转换为占位文本，避免摘要输入中出现不可辨认的空字段。
     *
     * @param value 原始标识符
     * @return 原值或 unknown 占位
     */
    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * 估算消息列表的 token 数量（字符数 / 比例）
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += messageContentForContext(msg).length() / TOKEN_CHARS_RATIO;
        }
        return total;
    }

    private String messageContentForContext(ChatMessage message) {
        if (message.getContent() != null) {
            if (message.getContent().startsWith("<task_notification>")) {
                return "";
            }
            return message.getContent();
        }
        if (!message.getToolCalls().isEmpty()) {
            return "tool_calls: " + message.getToolCalls();
        }
        return "";
    }

    /**
     * 将消息切分点对齐到 OpenAI tool calling 协议允许的消息边界。
     *
     * <p>如果候选切分点位于 assistant(tool_calls) 与其全部 tool 结果之间，
     * 则回退到该 assistant 消息之前，避免保留区以孤立的 tool 消息开头，
     * 也避免压缩区留下尚未获得完整结果的工具调用。</p>
     *
     * @param messages         待切分的完整消息列表
     * @param candidateSplitAt 候选切分点，表示右侧保留区的起始下标
     * @return 对齐后的切分点；未命中工具调用消息组时返回原候选值
     */
    static int alignSplitAtToToolCallBoundary(List<ChatMessage> messages, int candidateSplitAt) {
        if (candidateSplitAt <= 0 || candidateSplitAt >= messages.size()) {
            return candidateSplitAt;
        }

        int openToolCallGroupStart = -1;
        List<String> pendingToolCallIds = new ArrayList<>();

        for (int i = 0; i < candidateSplitAt; i++) {
            ChatMessage message = messages.get(i);
            if ("assistant".equals(message.getRole()) && !message.getToolCalls().isEmpty()) {
                if (pendingToolCallIds.isEmpty()) {
                    openToolCallGroupStart = i;
                }
                for (LlmToolCall toolCall : message.getToolCalls()) {
                    pendingToolCallIds.add(toolCall.id());
                }
                continue;
            }

            if ("tool".equals(message.getRole()) && openToolCallGroupStart >= 0) {
                pendingToolCallIds.remove(message.getToolCallId());
                if (pendingToolCallIds.isEmpty()) {
                    openToolCallGroupStart = -1;
                }
            }
        }

        return openToolCallGroupStart >= 0 ? openToolCallGroupStart : candidateSplitAt;
    }

    /**
     * 限制历史消息条数（目标为最近 20 条）
     *
     * <p>Token 预算由循环内的 compressIfNeeded 动态管理，这里只做条数限制。
     * 如果第 20 条落在工具调用消息组内部，则向前扩展以保留完整协议组。</p>
     *
     * @param history 原始历史消息
     * @return 裁剪后的历史消息，不会从孤立的 tool 消息开始
     */
    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        if (history.size() <= 20) {
            return new ArrayList<>(history);
        }
        int splitAt = alignSplitAtToToolCallBoundary(history, history.size() - 20);
        return new ArrayList<>(history.subList(splitAt, history.size()));
    }

    /**
     * 执行单个工具
     *
     * <p>包含超时控制和异常处理，确保单个工具失败不会导致整个 Agent 崩溃。</p>
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 执行结果字符串（成功返回结果，失败返回错误信息）
     */
    private String executeTool(String sessionId, String toolName, Map<String, Object> arguments) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "错误：未知工具 '" + toolName + "'";
        }
        try {
            return CompletableFuture.supplyAsync(() -> tool.execute(sessionId, arguments))
                    .orTimeout(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            log.error("工具执行超时 ({}s): {}", TOOL_TIMEOUT.getSeconds(), toolName);
                            return "工具执行超时（" + TOOL_TIMEOUT.getSeconds() + "秒），请重试或换一种方式";
                        }
                        log.error("Tool execution failed: {}", toolName, ex);
                        return "工具执行出错：" + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    })
                    .join();
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            return "工具执行出错：" + e.getMessage();
        }
    }

    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    /**
     * 执行工具并发送心跳（流式版本专用）
     *
     * <p>工具执行期间定期发送心跳事件，防止 SSE 连接因空闲被断开。</p>
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @param emitter   SSE 发射器
     * @return 执行结果
     */
    private String executeToolWithHeartbeat(String sessionId, String toolName,
                                            Map<String, Object> arguments,
                                            reactor.core.publisher.FluxSink<SseEvent> emitter) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "错误：未知工具 '" + toolName + "'";
        }

        try {
            // 启动心跳线程
            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicLong lastHeartbeat = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

            Thread heartbeatThread = new Thread(() -> {
                while (running.get() && !emitter.isCancelled()) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS);
                        if (running.get() && !emitter.isCancelled()) {
                            emitter.next(SseEvent.heartbeat());
                            lastHeartbeat.set(System.currentTimeMillis());
                            log.debug("发送心跳: {}", toolName);
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "heartbeat-" + toolName);
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            // 执行工具
            String result;
            try {
                result = CompletableFuture.supplyAsync(() -> tool.execute(sessionId, arguments))
                        .orTimeout(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> {
                            if (ex instanceof TimeoutException) {
                                log.error("工具执行超时 ({}s): {}", TOOL_TIMEOUT.getSeconds(), toolName);
                                return "工具执行超时（" + TOOL_TIMEOUT.getSeconds() + "秒），请重试或换一种方式";
                            }
                            log.error("Tool execution failed: {}", toolName, ex);
                            return "工具执行出错：" + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                        })
                        .join();
            } finally {
                // 停止心跳
                running.set(false);
                heartbeatThread.interrupt();
            }

            return result;

        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            return "工具执行出错：" + e.getMessage();
        }
    }

    /**
     * 构建完整的系统提示词。
     *
     * <p>提示词由 {@link ReactPromptAssembler} 按真实工具能力分段组装，动态上下文和任务策略统一放到尾部。</p>
     */
    private String buildSystemPrompt(String skillPrompt) {
        String prompt = ReactPromptAssembler.assemble(toolDefinitions, skillPrompt, plan);
        if (log.isDebugEnabled()) {
            log.debug("执行器提示词组装完成: sections={}, chars={}",
                    ReactPromptAssembler.sectionNames(toolDefinitions, skillPrompt, plan),
                    prompt.length());
        }
        return prompt;
    }

    /**
     * 获取当前 Agent 可用的工具定义列表
     *
     * @return 工具定义列表的副本
     */
    public List<ToolDefinition> getAvailableTools() {
        return new ArrayList<>(toolDefinitions);
    }

    // ==================== 流式版本 ====================

    /**
     * 流式执行 ReAct 循环 — 双层流式核心
     *
     * <h3>双层流式说明</h3>
     * <ul>
     *   <li>外层：步骤级事件（thinking_start、tool_call、observation 等）</li>
     *   <li>内层：token 级事件（LLM 输出的每个 token）</li>
     * </ul>
     *
     * <h3>中断支持</h3>
     * <p>通过 Flux.create 的 emitter.isCancelled() 检测客户端断开，
     * 在循环开始时检查，实现即时中断。</p>
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @param history     历史消息
     * @return SSE 事件流
     */
    public Flux<SseEvent> runStream(String sessionId, String userMessage, List<ChatMessage> history) {
        return Flux.create(emitter -> {
            FluxSink<SseEvent> sink = emitter;
            try {
                List<ChatMessage> messages = new ArrayList<>();
                messages.addAll(trimHistory(history));
                messages.add(ChatMessage.userMessage(userMessage));

                log.info("ReAct Stream 消息构建完成，历史 {} 条，当前消息 1 条", history.size());

                AtomicInteger totalPromptTokens = new AtomicInteger(0);
                AtomicInteger totalCompletionTokens = new AtomicInteger(0);
                AtomicInteger totalCacheHitTokens = new AtomicInteger(0);
                AtomicInteger totalTokens = new AtomicInteger(0);
                AtomicInteger iteration = new AtomicInteger(0);

                List<AgentStep> steps = new ArrayList<>();
                List<Map<String, Object>> persistedEvents = new ArrayList<>();
                Map<String, Object> planEvent = AgentEventMapper.planEvent(plan);
                if (planEvent != null) {
                    persistedEvents.add(planEvent);
                }

                // 循环执行
                while (!sink.isCancelled() && iteration.get() < MAX_ITERATIONS) {
                    iteration.incrementAndGet();

                    // 发送思考开始事件
                    sink.next(SseEvent.thinkingStart(iteration.get()));

                    // 注入后台任务完成通知
                    if (backgroundTaskManager != null) {
                        String notification = backgroundTaskManager.collect(sessionId);
                        if (notification != null) {
                            log.info("[后台子代理] 收集到通知并注入消息循环: {} 字符", notification.length());
                            messages.add(ChatMessage.userMessage(notification));
                            sink.next(SseEvent.status("后台子代理已完成，结果已注入对话"));
                            log.debug("[后台子代理] 通知内容预览: {}",
                                    notification.length() > 200 ? notification.substring(0, 200) + "..." : notification);
                        }
                    }

                    // 压缩历史消息（如果需要）
                    compressIfNeeded(messages);

                    String prompt = effectiveSystemPrompt();

                    // 累积本轮的思考内容和思考链
                    StringBuilder currentThinking = new StringBuilder();
                    StringBuilder currentReasoning = new StringBuilder();
                    AtomicReference<LlmToolCall> toolCallRef = new AtomicReference<>();
                    AtomicReference<LlmUsage> usageRef = new AtomicReference<>();

                    // 调用 LLM 流式 API
                    final int currentStep = iteration.get();

                    try {
                        llmService.chatWithToolsStream(prompt, messages, toolDefinitions)
                            .doOnNext(chunk -> {
                                if (sink.isCancelled()) return;

                                switch (chunk.type()) {
                                    case "token":
                                        currentThinking.append(chunk.content());
                                        sink.next(SseEvent.token(chunk.content()));
                                        break;
                                    case "reasoning":
                                        currentReasoning.append(chunk.reasoning());
                                        sink.next(SseEvent.reasoningToken(chunk.reasoning()));
                                        break;
                                    case "tool_call":
                                        toolCallRef.set(chunk.toolCall());
                                        break;
                                    case "finish":
                                        usageRef.set(chunk.usage());
                                        break;
                                }
                            })
                            .blockLast(); // 阻塞等待本轮 LLM 完成
                    } catch (Exception e) {
                        log.error("LLM 流式调用失败", e);
                        sink.next(SseEvent.error("LLM 调用失败: " + e.getMessage()));
                        sink.complete();
                        return;
                    }

                    // 累积 token 用量
                    if (usageRef.get() != null) {
                        LlmUsage usage = usageRef.get();
                        totalPromptTokens.addAndGet(usage.promptTokens());
                        totalCompletionTokens.addAndGet(usage.completionTokens());
                        totalCacheHitTokens.addAndGet(usage.cacheHitTokens());
                        totalTokens.addAndGet(usage.totalTokens());
                    }

                    // 发送思考结束事件
                    sink.next(SseEvent.thinkingEnd());

                    // 检查是否被中断
                    if (sink.isCancelled()) {
                        // 取消后台任务
                        if (backgroundTaskManager != null) {
                            backgroundTaskManager.cancelAll(sessionId);
                        }
                        // 保存中断时的 partial 内容
                        saveInterruptedMessages(currentThinking.toString(), currentReasoning.toString());
                        sink.next(SseEvent.interrupted("用户手动暂停"));
                        sink.complete();
                        return;
                    }

                    // 判断 LLM 返回的是工具调用还是最终答案
                    LlmToolCall toolCall = toolCallRef.get();
                    if (toolCall != null) {
                        String toolName = toolCall.name();
                        Map<String, Object> arguments = toolCall.arguments();
                        String displayReason = AgentActionNarrator.describe(toolName, arguments, plan);

                        log.info("执行工具: {} 参数: {}", toolName, arguments);

                        // 🪝 PreToolUse Hook：任一回调返回非 null 即阻止执行
                        String blocked = triggerPreToolUseHooks(toolCall, sessionId);
                        String observation;
                        long startTime;
                        if (blocked != null) {
                            log.info("工具 {} 被 Hook 阻止: {}", toolName, blocked);
                            observation = blocked;
                            startTime = System.currentTimeMillis();
                            // 不实际执行，但发送事件告知前端
                            sink.next(SseEvent.toolCall(toolName, arguments, currentStep));
                        } else {
                            // 发送工具调用事件
                            sink.next(SseEvent.toolCall(toolName, arguments, currentStep));

                            // 执行工具并发送心跳
                            startTime = System.currentTimeMillis();
                            observation = executeToolWithHeartbeat(sessionId, toolName, arguments, sink);
                        }
                        long durationMs = System.currentTimeMillis() - startTime;

                        // 🪝 PostToolUse Hook：仅在实际执行时触发
                        ChatMessage hookInjection = null;
                        if (blocked == null) {
                            hookInjection = triggerPostToolUseHooks(toolCall, observation, sessionId);
                        }

                        // 发送工具执行结果事件
                        sink.next(SseEvent.observation(toolName, observation, durationMs));

                        LlmToolCall historyToolCall = ensureToolCallId(toolCall);

                        // 记录本轮步骤
                        LlmToolResult toolResult = LlmToolResult.success(
                                historyToolCall.id(), toolName, observation, durationMs, displayReason);
                        AgentStep step = new AgentStep(currentStep, currentThinking.toString(),
                                currentReasoning.toString(), historyToolCall, toolResult, usageRef.get());
                        steps.add(step);
                        persistedEvents.addAll(AgentEventMapper.fromStep(step));

                        // 追加到消息历史（原生 tool calling 协议）
                        messages.add(ChatMessage.assistantToolCallMessage(historyToolCall));
                        messages.add(ChatMessage.toolMessage(historyToolCall.id(), observation));

                        // Hook 注入额外消息（如图片数据）
                        if (hookInjection != null) {
                            messages.add(hookInjection);
                            log.debug("PostToolUse Hook 注入消息: role={}", hookInjection.getRole());
                        }

                    } else {
                        // LLM 给出最终答案
                        log.info("ReAct Stream 完成，共 {} 次迭代", currentStep);

                        String finalContent = currentThinking.toString();
                        String finalReasoning = currentReasoning.toString();

                        // 🪝 Stop Hook：任一回调返回非 null 即注入消息并强制继续
                        String forceContinue = triggerStopHooks(messages);
                        if (forceContinue != null) {
                            log.info("Stop Hook 强制继续: {}", forceContinue.length() > 100 ? forceContinue.substring(0, 100) + "..." : forceContinue);
                            messages.add(ChatMessage.userMessage(forceContinue));
                            continue;
                        }

                        // 最终返回前等待遗留后台任务
                        if (backgroundTaskManager != null && backgroundTaskManager.isPending(sessionId)) {
                            log.info("[后台子代理] 最终返回前等待后台任务完成...");
                            sink.next(SseEvent.status("等待后台子代理完成..."));

                            String remaining = backgroundTaskManager.awaitPending(sessionId, 30_000);
                            int digestRound = 0;
                            while (remaining != null && !remaining.isEmpty()) {
                                digestRound++;
                                log.info("[后台子代理] 第 {} 轮消化: 通知长度 {} 字符",
                                        digestRound, remaining.length());
                                messages.add(ChatMessage.userMessage(remaining));
                                sink.next(SseEvent.status("后台子代理结果已返回，正在消化..."));

                                // 消化循环：LLM 看到通知后可能又调工具，最多 6 轮
                                for (int digest = 0; digest < 6; digest++) {
                                    if (sink.isCancelled()) break;

                                    log.info("[后台子代理] 消化步骤 {}/{} 开始", digest + 1, 6);

                                    LlmResponse digestResponse = llmService.chatWithTools(
                                            effectiveSystemPrompt(), messages, toolDefinitions);
                                    if (digestResponse.getTokenUsage() != null) {
                                        LlmUsage usage = digestResponse.getTokenUsage();
                                        totalPromptTokens.addAndGet(usage.promptTokens());
                                        totalCompletionTokens.addAndGet(usage.completionTokens());
                                        totalCacheHitTokens.addAndGet(usage.cacheHitTokens());
                                        totalTokens.addAndGet(usage.totalTokens());
                                    }
                                    if (digestResponse.isFinished()) {
                                        finalContent = digestResponse.getContent() != null
                                                ? digestResponse.getContent() : finalContent;
                                        finalReasoning = digestResponse.getReasoningContent() != null
                                                ? digestResponse.getReasoningContent() : finalReasoning;
                                        log.info("[后台子代理] 消化步骤 {}/{} LLM 返回最终答案，消化结束",
                                                digest + 1, 6);
                                        break;
                                    }
                                    if (digestResponse.hasToolCall()) {
                                        LlmToolCall tc = digestResponse.getToolCall();
                                        log.info("[后台子代理] 消化步骤 {}/{} 调用工具: {}",
                                                digest + 1, 6, tc.name());

                                        int digestStep = currentStep + digest + 1;
                                        sink.next(SseEvent.toolCall(tc.name(), tc.arguments(), digestStep));

                                        String obs = executeTool(sessionId, tc.name(), tc.arguments());
                                        LlmToolCall historyTc = ensureToolCallId(tc);
                                        messages.add(ChatMessage.assistantToolCallMessage(historyTc));
                                        messages.add(ChatMessage.toolMessage(historyTc.id(), obs));

                                        sink.next(SseEvent.observation(tc.name(), obs, 0));
                                        log.info("[后台子代理] 消化步骤 {}/{} 工具 {} 结果: {} 字符",
                                                digest + 1, 6, tc.name(),
                                                obs != null ? obs.length() : 0);
                                    }
                                }
                                // 继续收集可能在此期间完成的新通知
                                remaining = backgroundTaskManager.collect(sessionId);
                            }
                            log.info("[后台子代理] 所有后台任务处理完毕");
                        }

                        // 保存完成的助手消息
                        saveAssistantMessage(finalContent, finalReasoning, persistedEvents);

                        sink.next(SseEvent.answer(finalContent, finalReasoning));

                        LlmUsage totalUsage = new LlmUsage(
                                totalPromptTokens.get(),
                                totalCompletionTokens.get(),
                                totalTokens.get(),
                                totalCacheHitTokens.get());

                        sink.next(SseEvent.done(currentStep, totalUsage));
                        sink.complete();
                        return;
                    }
                }

                // 达到最大迭代次数
                if (!sink.isCancelled()) {
                    log.warn("ReAct Stream 达到最大迭代次数 ({})", MAX_ITERATIONS);
                    String limitMessage = "已达到最大执行次数，任务未完成。上方已保留本次执行过程。";
                    persistedEvents.add(Map.of(
                            "type", "status",
                            "content", limitMessage));

                    saveAssistantMessage(limitMessage, null, persistedEvents);
                    sink.next(SseEvent.status(limitMessage));
                    sink.next(SseEvent.answer(limitMessage, null));
                    LlmUsage totalUsage = new LlmUsage(
                            totalPromptTokens.get(),
                            totalCompletionTokens.get(),
                            totalTokens.get(),
                            totalCacheHitTokens.get());
                    sink.next(SseEvent.done(iteration.get(), totalUsage));
                    sink.complete();
                }

            } catch (Exception e) {
                log.error("ReAct Stream 执行失败", e);
                if (!sink.isCancelled()) {
                    sink.next(SseEvent.error("执行失败: " + e.getMessage()));
                    sink.complete();
                }
            }
        });
    }

    // ==================== 消息保存 ====================

    /**
     * 保存助手消息（带思考链）
     */
    private void saveAssistantMessage(String content, String reasoning) {
        saveAssistantMessage(content, reasoning, List.of());
    }

    /**
     * 保存助手消息、思考链和可恢复展示的执行过程事件。
     *
     * @param content   助手回复正文
     * @param reasoning 思考链内容，可为空
     * @param events    前端历史恢复所需的 plan、thinking、toolResult 等事件
     */
    private void saveAssistantMessage(String content, String reasoning, List<Map<String, Object>> events) {
        if (conversationService == null || sessionId == null) {
            return;
        }
        if (content != null && !content.isEmpty()) {
            conversationService.addAssistantMessage(sessionId, content,
                    reasoning != null && !reasoning.isEmpty() ? reasoning : null,
                    events != null ? events : List.of());
            log.info("【Stream 保存】助手消息: {} 字符, 思考链: {} 字符",
                    content.length(),
                    reasoning != null ? reasoning.length() : 0);
        }
    }

    /**
     * 保存中断时的消息
     *
     * <p>保存两条消息：</p>
     * <ol>
     *   <li>assistant: partial 内容（带 partial reasoning）</li>
     *   <li>user: 中断标记</li>
     * </ol>
     */
    private void saveInterruptedMessages(String partialContent, String partialReasoning) {
        if (conversationService == null || sessionId == null) {
            return;
        }

        // 1. 保存 partial assistant 消息
        if (partialContent != null && !partialContent.isEmpty()) {
            conversationService.addAssistantMessage(sessionId, partialContent,
                    partialReasoning != null && !partialReasoning.isEmpty() ? partialReasoning : null);
            log.info("【Stream 中断保存】partial 内容: {} 字符, partial 思考链: {} 字符",
                    partialContent.length(),
                    partialReasoning != null ? partialReasoning.length() : 0);
        }

        // 2. 保存用户中断标记
        conversationService.addUserMessage(sessionId, INTERRUPTED_MARKER);
        log.info("【Stream 中断保存】中断标记已保存");
    }
}
