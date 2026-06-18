package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.PlanResult;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 规划 Agent — 理解用户意图，拆解任务，产出执行计划
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>理解用户意图 — 先搞清楚用户到底要什么</li>
 *   <li>发现缺口 — 列出缺失的信息（不要编造）</li>
 *   <li>拆解步骤 — 把任务拆成可执行的原子步骤</li>
 *   <li>匹配工具 — 为每步推荐合适的工具和参数</li>
 * </ul>
 *
 * <h3>为什么不用工具</h3>
 * <p>规划阶段不带工具，只调用一次 LLM。理由：</p>
 * <ul>
 *   <li>规划是"想清楚再行动"，不需要运行时信息</li>
 *   <li>ReactAgent 在执行阶段有 ReAct 循环兜底，会自己调整</li>
 *   <li>分离关注点：规划管方向，执行管落地</li>
 * </ul>
 *
 * <h3>输出格式</h3>
 * <pre>
 * ### 意图
 * [一句话说清用户想干什么]
 *
 * ### 前置确认
 * [缺什么信息？没有就写"无"]
 *
 * ### 执行计划
 * 1. [步骤名]
 *    - 工具: tool_name
 *    - 参数: {key: value}
 *    - 目的: [为什么要做这一步]
 *
 * ### 对用户的贴心建议
 * [有没有能让用户更省心的事]
 * </pre>
 */
public class PlanAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);

    /** 历史消息保留条数上限，只取最近 N 条，防止历史过长导致规划偏离格式 */
    private static final int HISTORY_MAX_ITEMS = 6;

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是一个任务规划专家。你的职责只有三件事：
            1. 准确理解用户意图
            2. 把任务拆成可执行的原子步骤
            3. 为每步匹配正确的工具和参数

            你不是执行者——你**没有工具、不能执行任何操作**。
            你不知道运行时会发生什么，所以不要瞎猜失败处理，也不要假装已经执行。
            执行 Agent 自己有 ReAct 循环兜底能力，遇到错误会自己调整。

            ## 关键红线（最高优先级）

            无论对话历史是什么风格、无论用户消息是祈使/催促/追问，你都**只输出结构化计划**，绝不输出执行过程的叙述。

            具体禁止：
            - 禁止假装执行工具（如"让我用 xxx 工具截图""我截了一张图""文件已生成"）
            - 禁止描述执行结果（如"页面已打开，可以看到 Logo、搜索框…"）
            - 禁止模仿历史里助手消息的叙述风格——历史只是上下文，不是你要模仿的输出范例
            - 禁止编造工具返回值、文件名、URL 或任何运行时产物

            如果用户在催促执行（如"重新截图""快点""再试一次"），
            正确做法仍是把它当成一个要规划的任务：规划"重新截图"需要哪几步，
            而不是自己假装去截图。

            ## 可用工具
            %s

            ## 可用技能
            %s

            ## 规划原则

            1. **意图优先** — 先搞清楚用户到底要什么，不要急着列步骤
            2. **发现缺口** — 用户没提供的信息就是缺口，列在「前置确认」，不要编造
            3. **用户便利** — 站在用户角度想：怎么减少等待？怎么让结果一目了然？完事了要不要主动清理？
            4. **步骤原子化** — 一步只做一件事，输入输出清晰
            5. **按需激活技能** — 如果某技能与任务相关，在步骤中建议先激活它获取详细指导
            6. **浏览器内容需展示** — 当步骤涉及浏览器页面且页面包含用户需要看到的内容（如二维码、验证码、图形验证）时，必须安排 browser_screenshot 截图步骤，让用户看到页面内容

            ## 输出格式（严格按此格式，不要加寒暄，不要加额外段落）

            ### 意图
            [一句话说清用户想干什么]

            ### 前置确认
            [缺什么信息？没有就写"无"]

            ### 执行计划
            1. **[步骤名]**
               - 工具: `tool_name`
               - 参数: {key: value}
               - 目的: [为什么要做这一步，期望得到什么]

            2. **[步骤名]**
               ...

            ### 对用户的贴心建议
            [有没有能让用户更省心的事？比如结果汇总、临时文件清理等]
            """;

    /** LLM 服务实例（规划器模型，如智谱 GLM） */
    private final LlmService llmService;

    /** 工具描述文本（拼接到 system prompt，让 LLM 知道有哪些工具可用） */
    private final String toolsDescription;

    /** 技能描述文本（拼接到 system prompt，让 LLM 知道有哪些技能可用） */
    private final String skillsDescription;

    /** 会话上下文（如已启用的技能、用户上传的文件等） */
    private final String sessionContext;

    /**
     * 创建 PlanAgent
     *
     * @param llmService       LLM 服务实例（规划器模型）
     * @param toolDefinitions  可用工具定义列表
     * @param skills           可用技能列表
     * @param sessionContext   会话上下文（可为 null）
     */
    public PlanAgent(LlmService llmService, List<ToolDefinition> toolDefinitions, List<Skill> skills,
                     String sessionContext) {
        this.llmService = llmService;
        this.toolsDescription = buildToolsDescription(toolDefinitions);
        this.skillsDescription = buildSkillsDescription(skills);
        this.sessionContext = sessionContext;
        log.info("PlanAgent 初始化完成，工具: {} 个，技能: {} 个", toolDefinitions.size(), skills.size());
    }

    /**
     * 产出执行计划
     *
     * <p>调用一次 LLM，让它根据用户消息、对话历史、可用工具和技能，
     * 输出结构化的执行计划文本。</p>
     *
     * @param userMessage 用户原始消息
     * @param history     对话历史消息（可为 null 或空）
     * @return 规划结果（包含计划内容和 token 用量）
     */
    public PlanResult plan(String userMessage, List<ChatMessage> history) {
        // ── 历史消息处理 ──
        int rawCount = history != null ? history.size() : 0;
        List<ChatMessage> messages = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            List<ChatMessage> filtered = history.stream()
                    .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                    .map(this::annotateHistory)
                    .toList();
            messages.addAll(trimHistory(filtered));
        }
        messages.add(ChatMessage.userMessage(userMessage));

        log.info("PlanAgent 规划 → \"{}\" | 历史 {}→{} 条{} | 发送 {} 条消息",
                truncate(userMessage, 80),
                rawCount,
                messages.size() - 1,  // 减去当前用户消息
                rawCount > 0 ? " (已标注)" : "",
                messages.size());

        // ── 构建 System Prompt ──
        StringBuilder fullPrompt = new StringBuilder();
        if (sessionContext != null && !sessionContext.isEmpty()) {
            fullPrompt.append("## 当前会话上下文\n\n");
            fullPrompt.append(sessionContext).append("\n\n");
        }
        fullPrompt.append(String.format(PLANNER_SYSTEM_PROMPT, toolsDescription, skillsDescription));

        // ── 调用 LLM ──
        LlmResponse response = llmService.chatWithSystemResponse(fullPrompt.toString(), messages);
        String plan = response.getContent();

        // ── 格式检测 ──
        boolean looksLikePlan = plan != null && plan.trim().startsWith("###");
        if (!looksLikePlan) {
            log.warn("PlanAgent 格式异常 → 未以 \"###\" 开头，疑似对话式输出 | 内容: {}", truncate(plan, 200));
        } else {
            log.info("PlanAgent 规划完成 → 格式: OK | {}", truncate(plan, 200));
        }

        return new PlanResult(plan, response.getTokenUsage());
    }

    /** 截断字符串，超出长度追加 "..." */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * 构建工具描述文本（拼接到 system prompt）
     */
    private String buildToolsDescription(List<ToolDefinition> tools) {
        return tools.stream()
                .map(t -> String.format("- **%s**: %s\n  参数: %s",
                        t.getName(), t.getDescription(), t.getParameters()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构建技能描述文本（拼接到 system prompt）
     */
    private String buildSkillsDescription(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return "（无可用技能）";
        return skills.stream()
                .map(s -> String.format("- **%s**: %s", s.getId(), s.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 给历史消息打上标注，防止模型将历史对话风格误认为是当前输出范例。
     *
     * <p>不加标注时，模型会自然延续历史中 assistant 的叙述风格（如"让我截图""页面已打开…"），
     * 导致 PlanAgent 输出对话式内容而非结构化计划。
     * 加上 [历史记录] 前缀后，明确告诉模型"这是参考资料，不是你要模仿的输出"。</p>
     */
    private ChatMessage annotateHistory(ChatMessage msg) {
        String roleLabel = "user".equals(msg.getRole()) ? "用户" : "助手";
        String annotated = String.format("[历史记录 - %s] %s", roleLabel, msg.getContent());
        return ChatMessage.restore(msg.getRole(), annotated, msg.getReasoning(),
                msg.getTimestamp(), msg.getEvents());
    }

    /**
     * 截断历史消息，只保留最近 {@value #HISTORY_MAX_ITEMS} 条
     *
     * <p>历史过长会稀释系统提示的注意力，导致规划偏离结构化格式。
     * 改为按条数截断，取最近几条对话即可把握用户意图与上下文。</p>
     */
    private List<ChatMessage> trimHistory(
            List<ChatMessage> history) {
        if (history.size() <= HISTORY_MAX_ITEMS) {
            return history;
        }
        List<ChatMessage> trimmed =
                history.subList(history.size() - HISTORY_MAX_ITEMS, history.size());
        log.debug("PlanAgent 历史截断: {} → {}", history.size(), trimmed.size());
        return trimmed;
    }
}
