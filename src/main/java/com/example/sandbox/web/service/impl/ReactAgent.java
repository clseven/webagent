package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.AgentEventMapper;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.model.llm.AgentRunStatus;
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
import com.example.sandbox.web.service.ToolSideEffect;
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
 * while (未完成 && 迭代次数 < 200) {
 *     1. LLM 思考：分析当前情况，决定下一步
 *     2. LLM 决策：调用工具 OR 直接回答
 *     3. 如果调工具 → 收集本轮全部 tool_call → 交 ToolScheduler 调度
 *        （READ 并发、WRITE/EXCLUSIVE 串行）→ 结果按原序追加消息 → 继续循环
 *     4. 如果回答 → Stop Hook 自检 → 放行则返回结果
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
    private static final int MAX_ITERATIONS = 200;

    /** 触发历史消息压缩的 token 阈值（字符数估算） */
    private static final int SUMMARIZE_THRESHOLD = 200_000;

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

    /** 构造时传入的原始动态提示内容，切换原始提示模式时用于重新组装。 */
    private final String promptContext;

    /** 系统提示词（包含工具说明、技能指导、运行时上下文和执行计划等） */
    private String systemPrompt;

    /** 本轮不可变运行时上下文；父子智能体共享同一时间快照。 */
    private final String runtimeContext;

    /** 规划阶段产出的执行计划（注入到 system prompt 中指导执行） */
    private final String plan;

    /** 对话摘要（当历史消息超出 token 预算时生成），实际作为 user 消息保留在 messages 中 */
    private String conversationSummary;

    /** 收尾尝试计数，用于 Hook 日志和诊断；实际执行工具后清零。 */
    private int finalizeAttemptCount = 0;

    /**
     * 正在等待自检确认的候选答案。
     *
     * <p>Stop Hook 发起候选验证后保存首版答案；下一轮模型不调用工具而直接收尾时，
     * 视为自检通过并原样返回此候选，避免自检轮重新措辞覆盖原答案。</p>
     */
    private volatile FinalAnswerCandidate pendingFinalAnswerCandidate;

    /**
     * 工具调用并发调度器。READ 类并发、WRITE/EXCLUSIVE 串行，结果按原序对齐。
     * 单实例只跑一条路径，复用即可。默认启用并发；可由 {@link #setConcurrentToolExecutionEnabled}
     * 在装配时关闭，退化为串行遍历（回滚开关）。
     */
    private ToolScheduler toolScheduler = new ToolScheduler();

    /**
     * 设置是否启用工具并发执行（由装配层按配置传入）。
     *
     * @param enabled true 并发；false 退化为串行遍历
     */
    public void setConcurrentToolExecutionEnabled(boolean enabled) {
        this.toolScheduler = new ToolScheduler(ToolScheduler.DEFAULT_READ_CONCURRENCY, enabled);
    }

    /**
     * 是否跳过 {@link ReactPromptAssembler}，直接把传入的 skillPrompt 当作完整 system prompt。
     *
     * <p>社交轮由装配层置 true 并传入 {@link ReactPromptAssembler#assembleSocial()} 的输出，
     * 避免加载 IDENTITY/WORKSPACE/工具清单等任务段，让闲聊场景不被工具定义或工作目录诱导。</p>
     */
    private boolean useRawSystemPrompt = false;

    /**
     * 设置是否跳过提示词组装。
     *
     * @param useRawSystemPrompt true 表示直接使用传入的 skillPrompt 作为完整 system prompt
     */
    public void setUseRawSystemPrompt(boolean useRawSystemPrompt) {
        this.useRawSystemPrompt = useRawSystemPrompt;
        this.systemPrompt = buildSystemPrompt(promptContext);
    }

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

    /** Stop Hook 对候选答案的处理动作。 */
    public enum StopAction {
        /** 当前候选答案可直接返回。 */
        ALLOW,
        /** 注入提示并继续执行，但不保留当前候选答案。 */
        CONTINUE,
        /** 保存当前候选答案，注入提示并等待下一轮自检确认。 */
        VERIFY_CANDIDATE
    }

    /**
     * Stop Hook 决策。
     *
     * @param action 处理动作
     * @param message 继续执行时注入的 user 消息；放行时可为空
     */
    public record StopDecision(StopAction action, String message) {

        /**
         * 创建直接放行决策。
         *
         * @return 放行决策
         */
        public static StopDecision allow() {
            return new StopDecision(StopAction.ALLOW, null);
        }

        /**
         * 创建普通强制继续决策。
         *
         * @param message 注入给模型的提醒
         * @return 强制继续决策
         */
        public static StopDecision continueWith(String message) {
            return new StopDecision(StopAction.CONTINUE, message);
        }

        /**
         * 创建候选答案验证决策。
         *
         * @param message 注入给模型的自检提示
         * @return 保存候选并继续自检的决策
         */
        public static StopDecision verifyCandidate(String message) {
            return new StopDecision(StopAction.VERIFY_CANDIDATE, message);
        }

        /**
         * 判断是否需要继续下一轮。
         *
         * @return true 表示应注入消息并继续
         */
        public boolean shouldContinue() {
            return action != StopAction.ALLOW;
        }
    }

    /**
     * 停止前 Hook：接收当前消息列表和本会话已连续收尾的次数，返回结构化收尾决策。
     */
    @FunctionalInterface
    public interface StopHook {
        /**
         * @param messages        当前对话消息
         * @param finalizeAttempt 本会话内模型连续准备收尾的次数（从 1 开始，第一次收尾时为 1）
         * @return 收尾决策，不应返回 null
         */
        StopDecision run(List<ChatMessage> messages, int finalizeAttempt);
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
     * 触发 PostToolUse 事件：遍历所有注册的回调，全部执行并收集注入消息。
     *
     * <p>与 Pre/Stop 不同，PostToolUse 不短路：每个回调都会执行，所有返回非 null 的
     * {@link ChatMessage} 都会被收集，由调用方依次追加到对话历史。这保证多个
     * PostToolUse Hook（如大输出告警 + 图片观察注入）能各自补充上下文、互不遮挡。</p>
     *
     * @return 需要注入的消息列表，可能为空，不会为 null
     */
    @SuppressWarnings("unchecked")
    private List<ChatMessage> triggerPostToolUseHooks(LlmToolCall toolCall, String result, String sessionId) {
        List<Object> callbacks = hooks.get("PostToolUse");
        if (callbacks == null) return List.of();
        List<ChatMessage> injections = new ArrayList<>();
        for (Object cb : callbacks) {
            ChatMessage extra = ((PostToolUseHook) cb).run(toolCall, result, sessionId);
            if (extra != null) {
                injections.add(extra);
            }
        }
        return injections;
    }

    /**
     * 触发 Stop 事件：遍历所有注册的回调，首个要求继续的决策会阻止退出。
     *
     * <p>调用前先递增收尾尝试计数并传给 hook；返回 null（放行）时计数无需清零——真正的清零发生在
     * 模型实际调用工具时（见 {@link #onToolExecuted()}）。这样“连续多次想收尾却不补查”才会累积计数，
     * 触发 hook 的强制放行防线。</p>
     *
     * @return Stop Hook 汇总决策
     */
    @SuppressWarnings("unchecked")
    private StopDecision triggerStopHooks(List<ChatMessage> messages) {
        List<Object> callbacks = hooks.get("Stop");
        if (callbacks == null) return StopDecision.allow();
        finalizeAttemptCount++;
        for (Object cb : callbacks) {
            StopDecision decision = ((StopHook) cb).run(messages, finalizeAttemptCount);
            if (decision != null && decision.shouldContinue()) {
                return decision;
            }
        }
        return StopDecision.allow();
    }

    /**
     * 模型实际执行了一次工具，重置收尾尝试计数。
     *
     * <p>语义：模型“认真补查”了，把机会重新给它——下次收尾自检从第 1 次重新计。
     * 只在工具真正执行（未被 Pre Hook 拦截）时调用。</p>
     */
    private void onToolExecuted() {
        finalizeAttemptCount = 0;
    }

    /**
     * 模型在候选答案自检期间选择调用工具，说明候选未通过，应立即失效。
     */
    private void invalidatePendingFinalAnswerCandidate() {
        pendingFinalAnswerCandidate = null;
    }

    /**
     * 保存等待自检确认的候选答案。
     *
     * @param content 候选正文
     * @param reasoning 候选正文对应的推理内容
     */
    private void rememberFinalAnswerCandidate(String content, String reasoning) {
        pendingFinalAnswerCandidate = new FinalAnswerCandidate(content, reasoning);
    }

    /**
     * 取出并清除已经通过自检的候选答案。
     *
     * @return 待放行候选；不存在时返回 null
     */
    private FinalAnswerCandidate consumeFinalAnswerCandidate() {
        FinalAnswerCandidate candidate = pendingFinalAnswerCandidate;
        pendingFinalAnswerCandidate = null;
        return candidate;
    }

    /** 自检前保存的候选答案快照。 */
    private record FinalAnswerCandidate(String content, String reasoning) {
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
                null,   // 子代理不启用后台任务
                this.runtimeContext // 继承父智能体本轮时间快照
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
        this(llmService, toolList, skillPrompt, plan, conversationService, sessionId,
                backgroundTaskManager, null);
    }

    /**
     * 创建带单轮运行时上下文的 ReactAgent。
     *
     * @param llmService LLM 服务实例
     * @param toolList 可用工具列表
     * @param skillPrompt 技能、知识库和工作区等动态提示内容
     * @param plan 规划器产出的任务策略
     * @param conversationService 对话服务；为 null 时不保存消息
     * @param sessionId 会话 ID
     * @param backgroundTaskManager 后台任务管理器；为 null 时不处理后台通知
     * @param runtimeContext 本轮不可变运行时上下文；可为 null
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt, String plan,
                      ConversationService conversationService, String sessionId,
                      BackgroundTaskManager backgroundTaskManager, String runtimeContext) {
        this.llmService = llmService;
        this.conversationService = conversationService;
        this.sessionId = sessionId;
        this.backgroundTaskManager = backgroundTaskManager;
        this.tools = new ConcurrentHashMap<>();
        this.toolDefinitions = new ArrayList<>();
        this.plan = plan;
        this.promptContext = skillPrompt;
        this.runtimeContext = runtimeContext;

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
     *   <li>如果 LLM 返回工具调用 → 收集全部 tool_call 交 ToolScheduler 调度
     *       （单调用走快捷路径，多调用按副作用分类并发/串行）→ 结果按原序追加消息 → 继续循环</li>
     *   <li>如果 LLM 返回最终答案 → Stop Hook 自检 → 放行则结束循环返回结果</li>
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
        restoreConversationSummary(messages);
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
                    // 新的后台证据到达后，旧候选尚未基于该证据生成，必须重新回答并验证。
                    invalidatePendingFinalAnswerCandidate();
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
                FinalAnswerCandidate verifiedCandidate = consumeFinalAnswerCandidate();
                if (verifiedCandidate != null) {
                    // 自检轮没有调用工具而是再次收尾，说明候选通过；忽略自检轮重写内容。
                    response = LlmResponse.text(
                            verifiedCandidate.content(),
                            verifiedCandidate.reasoning(),
                            response.getTokenUsage());
                    log.info("回答前自检通过，原样放行候选答案");
                } else {
                    // 🪝 Stop Hook：首个要求继续的决策会注入消息并进入下一轮
                    StopDecision stopDecision = triggerStopHooks(messages);
                    if (stopDecision.shouldContinue()) {
                        if (stopDecision.action() == StopAction.VERIFY_CANDIDATE) {
                            rememberFinalAnswerCandidate(response.getContent(), response.getReasoningContent());
                        }
                        String forceContinue = stopDecision.message();
                        log.info("Stop Hook 强制继续: {}", forceContinue.length() > 100 ? forceContinue.substring(0, 100) + "..." : forceContinue);
                        messages.add(ChatMessage.userMessage(forceContinue));
                        continue;
                    }
                }

                // 最终返回前等待遗留后台任务
                if (backgroundTaskManager != null && backgroundTaskManager.isPending(sessionId)) {
                    log.info("最终返回前等待后台任务...");
                    String remaining = backgroundTaskManager.awaitPending(sessionId, 30_000);
                    if (remaining != null) {
                        messages.add(ChatMessage.userMessage(remaining));
                        // 消化循环：LLM 看到通知后可能又调工具，最多 6 轮。
                        // 注意：此处用裸 executeTool，不走 executeOneToolWithHooks，即不触发
                        // Pre/Post Hook（含 State Checks 写后 invalidate）、不重置收尾自检计数、
                        // 也不按并发协议组装多 tool_call 消息——digest 为后台任务消化的简化路径。
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
                // 自检选择补查即表示当前候选不通过；即使工具稍后被 Pre Hook 拦截也不能复用旧答案。
                invalidatePendingFinalAnswerCandidate();
                List<LlmToolCall> toolCalls = response.getToolCalls();
                String llmContent = response.getContent();
                if (llmContent != null && !llmContent.isEmpty()) {
                    log.debug("LLM 思考: {}", llmContent.length() > 500 ? llmContent.substring(0, 500) + "..." : llmContent);
                }

                // 一轮可能多个 tool_call：并发执行（READ 并发、WRITE/EXCLUSIVE 串行），结果按原序对齐
                if (toolCalls.size() == 1) {
                    ToolExecResult tr = executeOneToolWithHooks(sessionId, toolCalls.get(0));
                    LlmToolCall historyToolCall = ensureToolCallId(toolCalls.get(0));
                    addToolStep(steps, iteration, llmContent, response, historyToolCall, tr);
                    messages.add(ChatMessage.assistantToolCallMessage(historyToolCall));
                    messages.add(ChatMessage.toolMessage(historyToolCall.id(), tr.observation()));
                    for (ChatMessage inj : tr.injections()) {
                        messages.add(inj);
                    }
                } else {
                    log.info("本轮并发工具调用 {} 个: {}", toolCalls.size(),
                            toolCalls.stream().map(LlmToolCall::name).toList());
                    List<ToolExecResult> results = toolScheduler.execute(
                            toolCalls,
                            tc -> classifyToolSideEffect(tc.name()),
                            tc -> executeOneToolWithHooks(sessionId, tc));
                    List<LlmToolCall> historyCalls = new ArrayList<>();
                    for (LlmToolCall tc : toolCalls) {
                        historyCalls.add(ensureToolCallId(tc));
                    }
                    for (int i = 0; i < toolCalls.size(); i++) {
                        addToolStep(steps, iteration, llmContent, response, historyCalls.get(i), results.get(i));
                    }
                    // 并发 tool calling 协议：一条 assistant 消息带全部 tool_calls，再各跟一条 tool 结果
                    messages.add(ChatMessage.assistantToolCallsMessage(historyCalls));
                    for (int i = 0; i < toolCalls.size(); i++) {
                        messages.add(ChatMessage.toolMessage(historyCalls.get(i).id(), results.get(i).observation()));
                    }
                    for (ToolExecResult tr : results) {
                        for (ChatMessage inj : tr.injections()) {
                            messages.add(inj);
                        }
                    }
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
                ConversationServiceImpl.SYNC_ITERATION_LIMIT_MESSAGE,
                null, steps, totalUsage, iteration,
                AgentRunStatus.PAUSED_MAX_ITERATIONS,
                List.copyOf(messages));
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
     * 复制调用方准备好的历史消息。
     *
     * <p>普通数据库历史已由会话服务限制条数；协议检查点可能超过 20 条，
     * 必须完整保留 tool_call 与 tool 结果，不能在 Agent 内再次按条数裁剪。
     * Token 预算统一由 {@link #compressIfNeeded(List)} 管理。</p>
     *
     * @param history 调用方准备好的历史消息或协议检查点
     * @return 可由当前运行安全修改的历史副本
     */
    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        return new ArrayList<>(history);
    }

    /**
     * 从恢复的压缩摘要消息中重建摘要状态。
     *
     * <p>检查点恢复后如果再次触发压缩，需要把旧摘要与新摘要合并；否则旧摘要会被
     * 当作普通消息重复概括并丢失增量语义。</p>
     *
     * @param messages 当前运行的历史消息
     */
    private void restoreConversationSummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ChatMessage first = messages.get(0);
        if (!"user".equals(first.getRole()) || first.getContent() == null
                || !first.getContent().startsWith(COMPACTED_SUMMARY_PREFIX)) {
            return;
        }
        conversationSummary = first.getContent().substring(COMPACTED_SUMMARY_PREFIX.length());
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

    /**
     * 单个工具调用的完整执行结果：observation + Post Hook 注入消息 + 耗时 + 行动说明。
     *
     * @param observation    工具结果（含被 Hook 拦截时的拦截原因）
     * @param injections     Post Hook 注入的额外消息
     * @param durationMs     执行耗时
     * @param displayReason  面向用户的行动说明
     */
    private record ToolExecResult(
            String observation,
            List<ChatMessage> injections,
            long durationMs,
            String displayReason
    ) {
    }

    /**
     * 执行单个工具调用的完整流程：Pre Hook 拦截判定 → 实际执行 → Post Hook 收集注入。
     * 同步与流式路径共用，保证 Hook 语义一致。任务内异常自身消化，不向调度器外抛。
     *
     * @param sessionId 会话 ID
     * @param toolCall  工具调用
     * @return 执行结果（observation + 注入消息 + 耗时 + 行动说明）
     */
    private ToolExecResult executeOneToolWithHooks(String sessionId, LlmToolCall toolCall) {
        String toolName = toolCall.name();
        Map<String, Object> arguments = toolCall.arguments();
        String displayReason = AgentActionNarrator.describe(toolName, arguments, plan);
        long startTime = System.currentTimeMillis();

        log.info("执行工具: {} 参数: {}", toolName, arguments);

        // 🪝 PreToolUse Hook：任一回调返回非 null 即阻止执行
        String blocked = triggerPreToolUseHooks(toolCall, sessionId);
        String observation;
        if (blocked != null) {
            log.info("工具 {} 被 Hook 阻止: {}", toolName, blocked);
            observation = blocked;
        } else {
            observation = executeTool(sessionId, toolName, arguments);
            // 模型认真补查了，重置收尾自检计数
            onToolExecuted();
        }
        long durationMs = System.currentTimeMillis() - startTime;

        // 🪝 PostToolUse Hook：仅在实际执行时触发，全部跑完收集所有注入消息
        List<ChatMessage> injections = blocked == null
                ? triggerPostToolUseHooks(toolCall, observation, sessionId)
                : List.of();
        if (observation != null) {
            log.debug("工具结果: {}", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);
        }
        return new ToolExecResult(observation, injections, durationMs, displayReason);
    }

    /**
     * 把一次工具调用记录为一个 AgentStep。
     *
     * @param steps            步骤列表
     * @param iteration        当前迭代
     * @param llmContent       本轮 LLM 思考内容
     * @param response         本轮 LLM 响应
     * @param historyToolCall  带稳定 id 的工具调用
     * @param tr               执行结果
     */
    private void addToolStep(List<AgentStep> steps, int iteration, String llmContent,
                             LlmResponse response, LlmToolCall historyToolCall, ToolExecResult tr) {
        LlmToolResult toolResult = LlmToolResult.success(
                historyToolCall.id(), historyToolCall.name(), tr.observation(), tr.durationMs(), tr.displayReason());
        steps.add(new AgentStep(iteration, llmContent, response.getReasoningContent(),
                historyToolCall, toolResult, response.getTokenUsage()));
    }

    /**
     * 按工具名查副作用类型（供调度器分类）。未知工具保守当 EXCLUSIVE 串行。
     *
     * @param toolName 工具名
     * @return 副作用类型
     */
    private ToolSideEffect classifyToolSideEffect(String toolName) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return ToolSideEffect.EXCLUSIVE;
        }
        try {
            return tool.getSideEffect();
        } catch (Exception e) {
            return ToolSideEffect.EXCLUSIVE;
        }
    }

    /**
     * 流式路径：执行单个工具调用并发送 SSE 事件（tool_call + observation）。
     * 与 {@link #executeOneToolWithHooks} 的 Hook 语义一致，区别在于执行用带心跳版本、并向前端发事件。
     *
     * @param sessionId   会话 ID
     * @param toolCall    工具调用
     * @param sink        SSE 发射器
     * @param currentStep 当前轮次
     * @return 执行结果
     */
    private ToolExecResult executeOneStreamToolWithHooks(String sessionId, LlmToolCall toolCall,
                                                        reactor.core.publisher.FluxSink<SseEvent> sink,
                                                        int currentStep) {
        String toolName = toolCall.name();
        Map<String, Object> arguments = toolCall.arguments();
        String displayReason = AgentActionNarrator.describe(toolName, arguments, plan);
        long startTime;

        log.info("执行工具: {} 参数: {}", toolName, arguments);

        // 🪝 PreToolUse Hook：任一回调返回非 null 即阻止执行
        String blocked = triggerPreToolUseHooks(toolCall, sessionId);
        String observation;
        if (blocked != null) {
            log.info("工具 {} 被 Hook 阻止: {}", toolName, blocked);
            observation = blocked;
            startTime = System.currentTimeMillis();
            // 不实际执行，但发送事件告知前端
            sink.next(SseEvent.toolCall(toolName, arguments, currentStep, displayReason, toolCall.id()));
        } else {
            // 发送工具调用事件
            sink.next(SseEvent.toolCall(toolName, arguments, currentStep, displayReason, toolCall.id()));
            // 执行工具并发送心跳
            startTime = System.currentTimeMillis();
            observation = executeToolWithHeartbeat(sessionId, toolName, arguments, sink);
            // 模型认真补查了，重置收尾自检计数
            onToolExecuted();
        }
        long durationMs = System.currentTimeMillis() - startTime;

        // 🪝 PostToolUse Hook：仅在实际执行时触发，全部跑完收集所有注入消息
        List<ChatMessage> injections = blocked == null
                ? triggerPostToolUseHooks(toolCall, observation, sessionId)
                : List.of();

        // 发送工具执行结果事件
        sink.next(SseEvent.observation(toolName, observation, durationMs, displayReason, toolCall.id()));
        return new ToolExecResult(observation, injections, durationMs, displayReason);
    }

    /**
     * 把一次流式工具调用记录为 AgentStep 并持久化事件。
     *
     * @param steps            步骤列表
     * @param currentStep      当前轮次
     * @param currentThinking  本轮思考内容
     * @param currentReasoning 本轮思考链
     * @param historyToolCall  带稳定 id 的工具调用
     * @param tr               执行结果
     * @param usageRef         token 用量
     * @param persistedEvents  持久化事件列表
     */
    private void addStreamToolStep(List<AgentStep> steps, int currentStep,
                                   StringBuilder currentThinking, StringBuilder currentReasoning,
                                   LlmToolCall historyToolCall, ToolExecResult tr,
                                   AtomicReference<LlmUsage> usageRef,
                                   List<Map<String, Object>> persistedEvents) {
        LlmToolResult toolResult = LlmToolResult.success(
                historyToolCall.id(), historyToolCall.name(), tr.observation(), tr.durationMs(), tr.displayReason());
        AgentStep step = new AgentStep(currentStep, currentThinking.toString(),
                currentReasoning.toString(), historyToolCall, toolResult, usageRef.get());
        steps.add(step);
        persistedEvents.addAll(AgentEventMapper.fromStep(step));
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
        if (useRawSystemPrompt) {
            return skillPrompt != null ? skillPrompt : "";
        }
        String prompt = ReactPromptAssembler.assemble(toolDefinitions, runtimeContext, skillPrompt, plan);
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
                restoreConversationSummary(messages);
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
                            // 新的后台证据到达后，旧候选尚未基于该证据生成，必须重新回答并验证。
                            invalidatePendingFinalAnswerCandidate();
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
                    // 自检确认轮的普通正文只表达“再次收尾”，不得在前端覆盖或闪现为新答案。
                    final boolean verifyingFinalCandidate = pendingFinalAnswerCandidate != null;
                    // 收集本轮全部 tool_call（流式可能跨多个 chunk、并发出多个）
                    java.util.List<LlmToolCall> toolCallsCollected = new java.util.concurrent.CopyOnWriteArrayList<>();
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
                                        if (!verifyingFinalCandidate) {
                                            sink.next(SseEvent.token(chunk.content()));
                                        }
                                        break;
                                    case "reasoning":
                                        currentReasoning.append(chunk.reasoning());
                                        sink.next(SseEvent.reasoningToken(chunk.reasoning()));
                                        break;
                                    case "tool_call":
                                        if (chunk.toolCall() != null) {
                                            toolCallsCollected.add(chunk.toolCall());
                                        }
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
                    if (!toolCallsCollected.isEmpty()) {
                        // 自检选择补查即表示当前候选不通过；即使工具稍后被 Pre Hook 拦截也不能复用旧答案。
                        invalidatePendingFinalAnswerCandidate();
                        List<LlmToolCall> toolCalls = new ArrayList<>(toolCallsCollected);

                        if (toolCalls.size() == 1) {
                            LlmToolCall historyToolCall = ensureToolCallId(toolCalls.get(0));
                            ToolExecResult tr = executeOneStreamToolWithHooks(sessionId, historyToolCall, sink, currentStep);
                            addStreamToolStep(steps, currentStep, currentThinking, currentReasoning,
                                    historyToolCall, tr, usageRef, persistedEvents);
                            messages.add(ChatMessage.assistantToolCallMessage(historyToolCall));
                            messages.add(ChatMessage.toolMessage(historyToolCall.id(), tr.observation()));
                            for (ChatMessage inj : tr.injections()) {
                                messages.add(inj);
                            }
                        } else {
                            log.info("本轮并发工具调用 {} 个: {}", toolCalls.size(),
                                    toolCalls.stream().map(LlmToolCall::name).toList());
                            List<LlmToolCall> historyCalls = new ArrayList<>();
                            for (LlmToolCall tc : toolCalls) {
                                historyCalls.add(ensureToolCallId(tc));
                            }
                            List<ToolExecResult> results = toolScheduler.execute(
                                    historyCalls,
                                    tc -> classifyToolSideEffect(tc.name()),
                                    tc -> executeOneStreamToolWithHooks(sessionId, tc, sink, currentStep));
                            for (int i = 0; i < historyCalls.size(); i++) {
                                addStreamToolStep(steps, currentStep, currentThinking, currentReasoning,
                                        historyCalls.get(i), results.get(i), usageRef, persistedEvents);
                            }
                            messages.add(ChatMessage.assistantToolCallsMessage(historyCalls));
                            for (int i = 0; i < historyCalls.size(); i++) {
                                messages.add(ChatMessage.toolMessage(historyCalls.get(i).id(), results.get(i).observation()));
                            }
                            for (ToolExecResult tr : results) {
                                for (ChatMessage inj : tr.injections()) {
                                    messages.add(inj);
                                }
                            }
                        }

                    } else {
                        // LLM 给出最终答案
                        log.info("ReAct Stream 完成，共 {} 次迭代", currentStep);

                        String finalContent = currentThinking.toString();
                        String finalReasoning = currentReasoning.toString();

                        FinalAnswerCandidate verifiedCandidate = consumeFinalAnswerCandidate();
                        if (verifiedCandidate != null) {
                            // 自检轮没有调用工具而是再次收尾，说明候选通过；最终事件仍使用自检前正文。
                            finalContent = verifiedCandidate.content();
                            finalReasoning = verifiedCandidate.reasoning();
                            log.info("回答前自检通过，原样放行流式候选答案");
                        } else {
                            // 🪝 Stop Hook：首个要求继续的决策会注入消息并进入下一轮
                            StopDecision stopDecision = triggerStopHooks(messages);
                            if (stopDecision.shouldContinue()) {
                                if (stopDecision.action() == StopAction.VERIFY_CANDIDATE) {
                                    rememberFinalAnswerCandidate(finalContent, finalReasoning);
                                }
                                String forceContinue = stopDecision.message();
                                log.info("Stop Hook 强制继续: {}", forceContinue.length() > 100 ? forceContinue.substring(0, 100) + "..." : forceContinue);
                                messages.add(ChatMessage.userMessage(forceContinue));
                                continue;
                            }
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

                                // 消化循环：LLM 看到通知后可能又调工具，最多 6 轮。
                                // 注意：此处用裸 executeTool（手动发 SSE 事件、手动记消息），
                                // 不走 executeOneStreamToolWithHooks，即不触发 Pre/Post Hook
                                // （含 State Checks）、不重置收尾自检计数、不记 AgentStep——
                                // digest 为后台任务消化的简化路径。
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
                                        LlmToolCall historyTc = ensureToolCallId(digestResponse.getToolCall());
                                        log.info("[后台子代理] 消化步骤 {}/{} 调用工具: {}",
                                                digest + 1, 6, historyTc.name());

                                        int digestStep = currentStep + digest + 1;
                                        String digestDisplayReason = AgentActionNarrator.describe(historyTc.name(), historyTc.arguments(), plan);
                                        sink.next(SseEvent.toolCall(historyTc.name(), historyTc.arguments(), digestStep, digestDisplayReason, historyTc.id()));

                                        String obs = executeTool(sessionId, historyTc.name(), historyTc.arguments());
                                        messages.add(ChatMessage.assistantToolCallMessage(historyTc));
                                        messages.add(ChatMessage.toolMessage(historyTc.id(), obs));

                                        sink.next(SseEvent.observation(historyTc.name(), obs, 0, digestDisplayReason, historyTc.id()));
                                        log.info("[后台子代理] 消化步骤 {}/{} 工具 {} 结果: {} 字符",
                                                digest + 1, 6, historyTc.name(),
                                                obs != null ? obs.length() : 0);
                                    }
                                }
                                // 继续收集可能在此期间完成的新通知
                                remaining = backgroundTaskManager.collect(sessionId);
                            }
                            log.info("[后台子代理] 所有后台任务处理完毕");
                        }

                        // 最终答案步骤的 thinking/reasoning 也需持久化到展示事件，避免历史刷新后思考链丢失
                        if (!currentThinking.isEmpty() || !currentReasoning.isEmpty()) {
                            AgentStep finalStep = new AgentStep(currentStep,
                                    currentThinking.toString(),
                                    currentReasoning.toString(),
                                    null, null, usageRef.get());
                            persistedEvents.addAll(AgentEventMapper.fromStep(finalStep));
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
                    String limitMessage = ConversationServiceImpl.STREAM_ITERATION_LIMIT_MESSAGE;
                    persistedEvents.add(Map.of(
                            "type", "status",
                            "content", limitMessage));

                    savePausedAssistantMessage(limitMessage, persistedEvents, messages);
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
     * 保存达到最大执行轮数时的暂停状态和精确协议检查点。
     *
     * @param content 面向用户展示的暂停提示
     * @param events 前端恢复执行过程所需的事件
     * @param checkpointMessages 下一轮继续执行所需的完整协议消息
     */
    private void savePausedAssistantMessage(String content, List<Map<String, Object>> events,
                                            List<ChatMessage> checkpointMessages) {
        if (conversationService == null || sessionId == null || content == null || content.isEmpty()) {
            return;
        }
        conversationService.addAssistantMessage(
                sessionId,
                content,
                null,
                events != null ? events : List.of(),
                AgentRunStatus.PAUSED_MAX_ITERATIONS,
                checkpointMessages != null ? List.copyOf(checkpointMessages) : List.of());
        log.info("【Stream 保存】达到最大执行轮数，保存 {} 条协议检查点消息",
                checkpointMessages != null ? checkpointMessages.size() : 0);
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
