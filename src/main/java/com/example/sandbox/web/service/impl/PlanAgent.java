package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.PlanResult;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是一个任务规划专家。你的职责只有三件事：
            1. 准确理解用户意图
            2. 把任务拆成可执行的原子步骤
            3. 为每步匹配正确的工具和参数

            你不是执行者——你不知道运行时会发生什么，所以不要瞎猜失败处理。
            执行 Agent 自己有 ReAct 循环兜底能力，遇到错误会自己调整。

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

            ## 输出格式

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
     * <p>调用一次 LLM，让它根据用户消息、可用工具和技能，
     * 输出结构化的执行计划文本。</p>
     *
     * @param userMessage 用户原始消息
     * @return 规划结果（包含计划内容和 token 用量）
     */
    public PlanResult plan(String userMessage) {
        log.info("PlanAgent 开始规划，用户消息长度: {} 字符", userMessage.length());

        String systemPrompt = String.format(PLANNER_SYSTEM_PROMPT, toolsDescription, skillsDescription);

        StringBuilder fullPrompt = new StringBuilder();
        if (sessionContext != null && !sessionContext.isEmpty()) {
            fullPrompt.append("## 当前会话上下文\n\n");
            fullPrompt.append(sessionContext).append("\n\n");
        }
        fullPrompt.append(systemPrompt);
        String finalSystemPrompt = fullPrompt.toString();

        LlmResponse response = llmService.chatWithSystemResponse(finalSystemPrompt,
                List.of(com.example.sandbox.web.model.entity.ChatMessage.userMessage(userMessage)));

        String plan = response.getContent();
        log.info("PlanAgent 规划完成，Plan 长度: {} 字符", plan != null ? plan.length() : 0);
        return new PlanResult(plan, response.getTokenUsage());
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
}
