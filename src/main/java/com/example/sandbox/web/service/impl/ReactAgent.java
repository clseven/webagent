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
            你是在真实环境中完成任务的执行者。

            计划提供方向，但不是事实。环境反馈才是你判断当前状态和选择下一步的依据。
            先理解当前所处的状态，再采取一个能够推进任务或减少不确定性的行动。
            行动后观察环境发生了什么，并据此更新你的判断。继续这个过程，
            直到观察到的状态与任务的成功信号相吻合。

            当结论依赖页面、文件、命令或其他运行时状态时，使用工具取得事实，
            不要用语言替代观察或操作。每次只调用一个工具，收到结果后再决定下一步。
            沙箱已经由系统按会话准备；通过提供的工具访问它，不假设宿主机状态。

            工具返回成功只说明一次动作发生了什么，不等于用户目标已经实现。
            判断是否完成时，应回到目标状态和成功信号，寻找能够直接支持结论的环境证据。
            如果实际情况与计划不符，修改策略，而不是维护计划。

            如果用户否定了先前结果，把反馈视为新的观察，重新审视导致该结果的假设。
            如果没有足够证据确认完成，继续调查；如果客观上无法验证，应如实说明。

            最终回复只陈述环境证据能够支持的结果，并清楚区分已完成、未完成和无法确认的部分。

            ## 技能系统（渐进式披露）

            技能用于补充特定任务的工作方法。只有当某项技能与当前目标相关时才加载，
            不需要为了遵循固定流程而先遍历全部技能。

            1. **skill_list** - 列出所有可用技能（简历模式）
               每个技能只显示 ID 和一句话描述，让你快速了解有哪些能力可用。

            2. **skill_activate** - 激活技能，加载完整指令
               当你判断某个技能与当前任务相关时，调用此工具获取详细指导。
               例如：`skill_activate(skill_id="brainstorming")`

            3. **skill_reference** - 读取技能的引用文件
               当技能指令中提到某个参考文档、模板时，使用此工具获取内容。

            ## 文件目录

            - /home/gem/uploads/ - 用户上传文件
            - /home/gem/workspace/ - 工作目录
            - /home/gem/output/ - 输出结果
            - /home/gem/skills/{skillId}/ - 技能文件
            - /home/gem/temp/ - 临时文件
            - 用 `list_files` 查看目录内容
            - 用 `read_file` 读取文件，用 `write_file` 保存文件

            当用户需要亲自看到页面中的视觉内容，例如二维码、验证码或图形结果时，
            使用 browser_screenshot 将当前画面呈现给用户。截图是一种观察和交付方式，
            它本身不负责证明页面语义或业务目标已经达成。

            ## MCP 动态工具管理

            当用户要求安装、接入或管理 MCP 时：

            1. 安装目标始终是当前 WebAgent。不要询问用户要安装到 VS Code、Claude Desktop、
               Claude Code、Cursor、Codex 或其他外部客户端；官方 README 中这些客户端的
               配置示例只用于确认协议和地址，不是当前任务的目标环境。
            2. 尚未核实 MCP 信息时，先使用 web_search 查找服务商官方文档，确认它是
               Streamable HTTP MCP，并核实精确 endpoint URL、主要能力和是否需要认证。
               URL 必须是官方客户端配置中可直接使用的地址，不能把官网、base URL 或根路径
               自动猜成 /mcp，也不能擅自添加、删除或替换路径。
            3. 安装前向用户展示来源、URL、可获得的能力和限制，等待用户明确确认。
            4. 如果对话历史中已经展示了最近一个待安装 MCP 的官方来源、URL、能力和限制，
               当前用户回复“确认”“可以”“安装吧”“就这个”等肯定表达，应直接视为确认该方案。
               使用历史中已经核实的信息立即调用 mcp_add_or_update_server，不要重复搜索，
               不要再次询问目标环境，也不要只用文字承诺稍后安装。
            5. 安装工具返回成功后调用 mcp_list_servers，验证连接状态和实际工具列表；
               验证失败时如实说明，不得宣称安装成功。
            6. 不要自行安装 stdio MCP，不要把 Token、API Key 或 Authorization headers
               写入沙箱配置。使用 mcp_reload 重新加载用户手动修改的配置。
            7. 新增 MCP 工具从下一条用户消息开始可用；当前执行轮次不要假设能立即调用。
            8. 如果 MCP 管理工具返回”客户端未启用”（即 agent.mcp.enabled=false），
               说明这是当前 WebAgent 后端进程的启动配置，不能通过用户沙箱修改。
               立即停止本次安装流程，向用户说明需要设置环境变量并重启后端；
               不得继续调用 shell、文件、浏览器或搜索工具寻找 application.yml、.env 或启动脚本。
            9. 如果 MCP 管理工具返回”连接失败”（包含 HTTP 错误、超时、拒绝连接等），
               说明 MCP 客户端已启用但连接目标 Server 失败。这是配置或网络问题，不是”未启用”。
               根据错误码向用户如实说明原因。HTTP 404/405 优先检查精确 endpoint；
               AUTH_REQUIRED 表示当前无认证版本不能安装；PROTOCOL_ERROR 表示目标不是兼容的
               Streamable HTTP MCP。修正 URL 后必须继续使用原 Server ID 更新配置，
               不得为了重试创建另一个 ID。
            10. 只有 initialize 和 tools/list 都成功，且 mcp_list_servers 显示已连接和真实工具列表，
                才能宣称安装成功。curl 或浏览器能访问 URL 只能证明网络可达，不能替代 MCP Client 验证。

            ## 可用工具

            %s
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

    // ==================== Hook 系统 ====================

    /**
     * 工具执行前 Hook：接收工具调用和会话 ID，返回 null 表示放行，返回非 null 字符串表示阻止执行（原因作为工具结果返回给 LLM）。
     */
    @FunctionalInterface
    public interface PreToolUseHook {
        String run(LlmToolCall toolCall, String sessionId, Map<String, Tool> tools);
    }

    /**
     * 工具执行后 Hook：接收工具调用、执行结果和会话 ID，无返回值（副作用型）。
     */
    @FunctionalInterface
    public interface PostToolUseHook {
        void run(LlmToolCall toolCall, String result, String sessionId);
    }

    /**
     * 停止前 Hook：接收当前消息列表，返回 null 表示允许退出，返回非 null 字符串表示强制继续（作为新 user 消息注入）。
     */
    @FunctionalInterface
    public interface StopHook {
        String run(List<ChatMessage> messages);
    }

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
     * 触发 PostToolUse 事件：遍历所有注册的回调（副作用型，不改变执行结果）。
     */
    @SuppressWarnings("unchecked")
    private void triggerPostToolUseHooks(LlmToolCall toolCall, String result, String sessionId) {
        List<Object> callbacks = hooks.get("PostToolUse");
        if (callbacks == null) return;
        for (Object cb : callbacks) {
            ((PostToolUseHook) cb).run(toolCall, result, sessionId);
        }
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
                // 🪝 Stop Hook：任一回调返回非 null 即注入消息并强制继续
                String forceContinue = triggerStopHooks(messages);
                if (forceContinue != null) {
                    log.info("Stop Hook 强制继续: {}", forceContinue.length() > 100 ? forceContinue.substring(0, 100) + "..." : forceContinue);
                    messages.add(ChatMessage.userMessage(forceContinue));
                    continue;
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

                // 🪝 PostToolUse Hook：仅在实际执行时触发（副作用型）
                if (blocked == null) {
                    triggerPostToolUseHooks(toolCall, observation, sessionId);
                }

                log.debug("工具结果: {}", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

                LlmToolCall historyToolCall = ensureToolCallId(toolCall);

                // 记录本轮步骤
                LlmToolResult toolResult = LlmToolResult.success(
                        historyToolCall.id(), toolName, observation, durationMs);
                AgentStep step = new AgentStep(iteration, llmContent, response.getReasoningContent(),
                        historyToolCall, toolResult, response.getTokenUsage());
                steps.add(step);

                // 追加到消息历史（原生 tool calling 协议）
                messages.add(ChatMessage.assistantToolCallMessage(historyToolCall));
                messages.add(ChatMessage.toolMessage(historyToolCall.id(), observation));
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

        log.info("压缩 {} 条旧消息为摘要 ({} 字符)，剩余 {} 条",
                oldMessages.size(), newSummary.length(), messages.size());
    }

    /**
     * 调用 LLM 生成摘要，压缩旧消息
     */
    private String summarizeMessages(List<ChatMessage> oldMessages) {
        StringBuilder history = new StringBuilder();
        for (ChatMessage msg : oldMessages) {
            String content = messageContentForContext(msg);
            String shortContent = content.length() > 500
                    ? content.substring(0, 500) + "..."
                    : content;
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
            total += messageContentForContext(msg).length() / TOKEN_CHARS_RATIO;
        }
        return total;
    }

    private String messageContentForContext(ChatMessage message) {
        if (message.getContent() != null) {
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
     * 构建完整的系统提示词
     *
     * <p>拼接顺序：任务策略 → 技能指导 → 基础执行提示。</p>
     */
    private String buildSystemPrompt(String skillPrompt) {
        StringBuilder toolsDesc = new StringBuilder();
        for (ToolDefinition tool : toolDefinitions) {
            toolsDesc.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }

        String basePrompt = String.format(REACT_SYSTEM_PROMPT, toolsDesc);

        StringBuilder fullPrompt = new StringBuilder();

        if (plan != null && !plan.isEmpty()) {
            fullPrompt.append("## 任务策略\n\n");
            fullPrompt.append("以下内容是策略层对任务的当前理解。它提供目标、判断和成功信号，");
            fullPrompt.append("但不是运行时事实，也不是必须照做的步骤清单。");
            fullPrompt.append("执行过程中以最新环境反馈为准，并在必要时修正策略。\n\n");
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
                        if (blocked == null) {
                            triggerPostToolUseHooks(toolCall, observation, sessionId);
                        }

                        // 发送工具执行结果事件
                        sink.next(SseEvent.observation(toolName, observation, durationMs));

                        LlmToolCall historyToolCall = ensureToolCallId(toolCall);

                        // 记录本轮步骤
                        LlmToolResult toolResult = LlmToolResult.success(
                                historyToolCall.id(), toolName, observation, durationMs);
                        AgentStep step = new AgentStep(currentStep, currentThinking.toString(),
                                currentReasoning.toString(), historyToolCall, toolResult, usageRef.get());
                        steps.add(step);
                        persistedEvents.addAll(AgentEventMapper.fromStep(step));

                        // 追加到消息历史（原生 tool calling 协议）
                        messages.add(ChatMessage.assistantToolCallMessage(historyToolCall));
                        messages.add(ChatMessage.toolMessage(historyToolCall.id(), observation));

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
                    sink.next(SseEvent.error("达到最大迭代次数，任务未完成"));
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
