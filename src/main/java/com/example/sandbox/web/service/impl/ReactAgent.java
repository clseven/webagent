package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
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
 * while (未完成 && 迭代次数 < 20) {
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
    private static final int MAX_ITERATIONS = 20;

    /** 触发历史消息压缩的 token 阈值（字符数估算） */
    private static final int SUMMARIZE_THRESHOLD = 24_000;

    /** 字符到 token 的估算比例（中文约 1.5-2 字符/token，取保守值 3） */
    private static final int TOKEN_CHARS_RATIO = 3;

    /** 单个工具执行的超时时间 */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(120);

    /** 用户中断标记（固定文本，有利于缓存命中） */
    private static final String INTERRUPTED_MARKER = "【用户手动暂停】任务被中断。";

    private static final String SUMMARIZE_PROMPT = """
            请用中文将以下对话历史压缩为一段简洁摘要（不超过 500 字），保留：
            - 用户的核心目标和意图
            - 已完成的关键操作和结果
            - 重要的发现或结论
            不要逐条复述，只提取关键信息。

            %s

            对话历史：
            %s""";

    private static final String REACT_SYSTEM_PROMPT = """
            你是一个智能助手。你必须通过调用工具来完成任务。

            ## 技能系统（渐进式披露）

            你拥有一个技能系统，通过三层方式使用：

            1. **skill_list** - 列出所有可用技能（简历模式）
               每个技能只显示 ID 和一句话描述，让你快速了解有哪些能力可用。

            2. **skill_activate** - 激活技能，加载完整指令
               当你判断某个技能与当前任务相关时，调用此工具获取详细指导。
               例如：`skill_activate(skill_id="brainstorming")`

            3. **skill_reference** - 读取技能的引用文件
               当技能指令中提到某个参考文档、模板时，使用此工具获取内容。

            **重要**：只有在判断某技能相关时才激活它，不相关的技能不要加载，以节省 token。

            ## 文件目录

            - /home/gem/uploads/ - 用户上传文件
            - /home/gem/workspace/ - 工作目录
            - /home/gem/output/ - 输出结果
            - /home/gem/skills/{skillId}/ - 技能文件
            - /home/gem/temp/ - 临时文件
            - 用 `list_files` 查看目录内容
            - 用 `read_file` 读取文件，用 `write_file` 保存文件

            ## 重要规则

            1. **必须调用工具** — 当需要执行命令、读写文件、激活技能时，必须调用对应工具
            2. **每次只调用一个工具** — 工具执行后你会收到结果，再决定下一步
            3. **沙箱是隔离环境** — 执行命令、运行代码、读写文件都需要先调用 request_sandbox 创建沙箱
            4. **不能编造结果** — 如果工具返回错误，根据错误信息调整，不要假装成功

            ## 可用工具

            %s

            ## 工作流程

            1. 分析用户需求
            2. 调用 skill_list 了解可用能力
            3. 激活相关技能获取详细指导
            4. 选择合适的工具执行任务
            5. 根据结果继续或给出最终答案
            """;

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

    /** 对话摘要（当历史消息超出 token 预算时，压缩旧消息生成），追加到 system prompt 前 */
    private String conversationSummary;

    /**
     * 创建 ReactAgent（无技能提示、无执行计划）
     *
     * @param llmService LLM 服务实例
     * @param toolList   可用工具列表
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList) {
        this(llmService, toolList, null, null, null, null);
    }

    /**
     * 创建 ReactAgent（带技能提示，无执行计划）
     *
     * @param llmService  LLM 服务实例
     * @param toolList    可用工具列表
     * @param skillPrompt 技能指导提示词（从技能文件加载）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt) {
        this(llmService, toolList, skillPrompt, null, null, null);
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
        this(llmService, toolList, skillPrompt, plan, null, null);
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
                      ConversationService conversationService, String sessionId) {
        this.llmService = llmService;
        this.conversationService = conversationService;
        this.sessionId = sessionId;
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
                long startTime = System.currentTimeMillis();
                String observation = executeTool(sessionId, toolName, arguments);
                long durationMs = System.currentTimeMillis() - startTime;
                log.debug("工具结果: {}", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

                // 记录本轮步骤
                LlmToolResult toolResult = LlmToolResult.success(toolCall.id(), toolName, observation, durationMs);
                AgentStep step = new AgentStep(iteration, llmContent, response.getReasoningContent(),
                        toolCall, toolResult, response.getTokenUsage());
                steps.add(step);

                // 追加到消息历史（原生 tool calling 协议）
                // 如果 toolCall.id() 为空（ReAct 文本回退），生成伪 id
                String toolCallId = toolCall.id();
                if (toolCallId == null || toolCallId.isBlank()) {
                    toolCallId = "fallback_" + System.currentTimeMillis();
                }
                messages.add(ChatMessage.assistantMessage(llmContent != null ? llmContent : ""));
                messages.add(ChatMessage.toolMessage(toolCallId, observation));
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

    /**
     * 构建有效的系统提示词（如有摘要则拼接在前面）
     */
    private String effectiveSystemPrompt() {
        if (conversationSummary == null || conversationSummary.isEmpty()) {
            return systemPrompt;
        }
        return "## 早期对话摘要\n" + conversationSummary + "\n\n" + systemPrompt;
    }

    /**
     * 如果消息总 token 超过阈值，把最旧的消息压缩为摘要。
     *
     * <p>保留最近 ~40% 的消息作为原始上下文，被压缩的消息从数组中移除，
     * 生成的摘要追加到 system prompt 前面，让 LLM 仍然能获取早期对话的关键信息。</p>
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
            threshold += messages.get(i).getContent().length() / TOKEN_CHARS_RATIO;
            if (threshold > totalTokens * 0.6) {
                splitAt = i;
                break;
            }
        }

        if (splitAt <= 2) {
            return; // 太少消息不值得压缩
        }

        List<ChatMessage> oldMessages = new ArrayList<>(messages.subList(0, splitAt));
        messages.subList(0, splitAt).clear();

        String newSummary = summarizeMessages(oldMessages);
        conversationSummary = newSummary;

        log.info("压缩 {} 条旧消息为摘要 ({} 字符)，剩余 {} 条",
                oldMessages.size(), newSummary.length(), messages.size());
    }

    /**
     * 调用 LLM 生成摘要，压缩旧消息
     */
    private String summarizeMessages(List<ChatMessage> oldMessages) {
        StringBuilder history = new StringBuilder();
        for (ChatMessage msg : oldMessages) {
            String shortContent = msg.getContent().length() > 500
                    ? msg.getContent().substring(0, 500) + "..."
                    : msg.getContent();
            history.append(msg.getRole()).append(": ").append(shortContent).append("\n\n");
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
     * 估算消息列表的 token 数量（字符数 / 比例）
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += msg.getContent().length() / TOKEN_CHARS_RATIO;
        }
        return total;
    }

    /**
     * 限制历史消息条数（最多保留最近 20 条）
     *
     * <p>Token 预算由循环内的 compressIfNeeded 动态管理，这里只做条数限制。</p>
     */
    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        if (history.size() <= 20) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - 20, history.size()));
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
     * 构建完整的系统提示词
     *
     * <p>拼接顺序：执行计划 → 技能指导 → 基础提示（工具列表、工作流程）</p>
     */
    private String buildSystemPrompt(String skillPrompt) {
        StringBuilder toolsDesc = new StringBuilder();
        for (ToolDefinition tool : toolDefinitions) {
            toolsDesc.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }

        String basePrompt = String.format(REACT_SYSTEM_PROMPT, toolsDesc);

        StringBuilder fullPrompt = new StringBuilder();

        if (plan != null && !plan.isEmpty()) {
            fullPrompt.append("## 执行计划（参考）\n\n");
            fullPrompt.append("以下是一份规划建议，用于指引方向，但你不必死板照做：\n");
            fullPrompt.append("- 优先参考计划的步骤和目的来推进任务\n");
            fullPrompt.append("- 如果某步失败了，先用其他工具排查原因，再决定重试、换方案还是跳过\n");
            fullPrompt.append("- 遇到计划外的情况，大胆调用计划里没写的工具来诊断和解决\n");
            fullPrompt.append("- 记住计划的最终目标，但到达目标的路径你可以灵活调整\n\n");
            fullPrompt.append(plan).append("\n\n");
        }

        if (skillPrompt != null && !skillPrompt.isEmpty()) {
            fullPrompt.append(skillPrompt).append("\n\n");
        }

        fullPrompt.append(basePrompt);
        return fullPrompt.toString();
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

                // 循环执行
                while (!emitter.isCancelled() && iteration.get() < MAX_ITERATIONS) {
                    iteration.incrementAndGet();

                    // 发送思考开始事件
                    emitter.next(SseEvent.thinkingStart(iteration.get()));

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
                                if (emitter.isCancelled()) return;

                                switch (chunk.type()) {
                                    case "token":
                                        currentThinking.append(chunk.content());
                                        emitter.next(SseEvent.token(chunk.content()));
                                        break;
                                    case "reasoning":
                                        currentReasoning.append(chunk.reasoning());
                                        emitter.next(SseEvent.reasoningToken(chunk.reasoning()));
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
                        emitter.next(SseEvent.error("LLM 调用失败: " + e.getMessage()));
                        emitter.complete();
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
                    emitter.next(SseEvent.thinkingEnd());

                    // 检查是否被中断
                    if (emitter.isCancelled()) {
                        // 保存中断时的 partial 内容
                        saveInterruptedMessages(currentThinking.toString(), currentReasoning.toString());
                        emitter.next(SseEvent.interrupted("用户手动暂停"));
                        emitter.complete();
                        return;
                    }

                    // 判断 LLM 返回的是工具调用还是最终答案
                    LlmToolCall toolCall = toolCallRef.get();
                    if (toolCall != null) {
                        String toolName = toolCall.name();
                        Map<String, Object> arguments = toolCall.arguments();

                        log.info("执行工具: {} 参数: {}", toolName, arguments);

                        // 发送工具调用事件
                        emitter.next(SseEvent.toolCall(toolName, arguments, currentStep));

                        // 执行工具并发送心跳
                        long startTime = System.currentTimeMillis();
                        String observation = executeToolWithHeartbeat(sessionId, toolName, arguments, emitter);
                        long durationMs = System.currentTimeMillis() - startTime;

                        // 发送工具执行结果事件
                        emitter.next(SseEvent.observation(toolName, observation, durationMs));

                        // 记录本轮步骤
                        LlmToolResult toolResult = LlmToolResult.success(toolCall.id(), toolName, observation, durationMs);
                        AgentStep step = new AgentStep(currentStep, currentThinking.toString(),
                                currentReasoning.toString(), toolCall, toolResult, usageRef.get());
                        steps.add(step);

                        // 追加到消息历史（原生 tool calling 协议）
                        String toolCallId = toolCall.id();
                        if (toolCallId == null || toolCallId.isBlank()) {
                            toolCallId = "fallback_" + System.currentTimeMillis();
                        }
                        messages.add(ChatMessage.assistantMessage(currentThinking.toString()));
                        messages.add(ChatMessage.toolMessage(toolCallId, observation));

                    } else {
                        // LLM 给出最终答案
                        log.info("ReAct Stream 完成，共 {} 次迭代", currentStep);

                        String finalContent = currentThinking.toString();
                        String finalReasoning = currentReasoning.toString();

                        // 保存完成的助手消息
                        saveAssistantMessage(finalContent, finalReasoning);

                        emitter.next(SseEvent.answer(finalContent, finalReasoning));

                        LlmUsage totalUsage = new LlmUsage(
                                totalPromptTokens.get(),
                                totalCompletionTokens.get(),
                                totalTokens.get(),
                                totalCacheHitTokens.get());

                        emitter.next(SseEvent.done(currentStep, totalUsage));
                        emitter.complete();
                        return;
                    }
                }

                // 达到最大迭代次数
                if (!emitter.isCancelled()) {
                    log.warn("ReAct Stream 达到最大迭代次数 ({})", MAX_ITERATIONS);
                    emitter.next(SseEvent.error("达到最大迭代次数，任务未完成"));
                    emitter.complete();
                }

            } catch (Exception e) {
                log.error("ReAct Stream 执行失败", e);
                if (!emitter.isCancelled()) {
                    emitter.next(SseEvent.error("执行失败: " + e.getMessage()));
                    emitter.complete();
                }
            }
        });
    }

    // ==================== 消息保存 ====================

    /**
     * 保存助手消息（带思考链）
     */
    private void saveAssistantMessage(String content, String reasoning) {
        if (conversationService == null || sessionId == null) {
            return;
        }
        if (content != null && !content.isEmpty()) {
            conversationService.addAssistantMessage(sessionId, content,
                    reasoning != null && !reasoning.isEmpty() ? reasoning : null);
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
