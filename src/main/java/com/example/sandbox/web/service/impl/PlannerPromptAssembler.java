package com.example.sandbox.web.service.impl;

/**
 * 规划器 system prompt 组装器。
 *
 * <h3>职责</h3>
 * <p>生成 PlanAgent 使用的策略层提示词，并按任务需要注入 MCP 相关约束。</p>
 *
 * <h3>边界</h3>
 * <p>规划器没有工具执行能力，提示词只描述任务模型契约和执行阶段能力边界。</p>
 */
final class PlannerPromptAssembler {

    /** 规划器修复提示，用于模型首次输出违反四段任务模型契约时重试。 */
    private static final String REPAIR_PROMPT = """
            你正在重新生成一份任务策略。上一次响应没有遵守策略层的输出契约。

            CRITICAL: 只输出纯文本，绝对不要调用任何工具或输出工具调用协议。

            只根据提供的对话资料生成任务模型。不要执行任务，不要承诺稍后执行，
            不要输出工具调用、函数调用、协议标记、寒暄或额外说明。

            必须按顺序输出且只输出以下四个非空段落：

            ### 目标状态
            ### 当前判断
            ### 成功信号
            ### 初始策略

            目标状态说明用户最终希望得到什么；当前判断区分事实、假设和待确认内容；
            成功信号必须能够由执行阶段观察；初始策略保持简洁并允许根据环境反馈调整。
            """;

    private PlannerPromptAssembler() {
    }

    /**
     * 组装规划器 system prompt。
     *
     * @param skillsDescription 技能摘要
     * @param sessionContext    当前会话上下文
     * @param includeMcpGuidance 是否注入 MCP 专用约束
     * @return 规划器 system prompt
     */
    static String assemble(String skillsDescription, String sessionContext, boolean includeMcpGuidance) {
        StringBuilder prompt = new StringBuilder();
        if (sessionContext != null && !sessionContext.isBlank()) {
            prompt.append("## 当前会话上下文\n\n");
            prompt.append(sessionContext.trim()).append("\n\n");
        }
        prompt.append("""
                你是任务的策略层。你的职责是帮助执行者建立一个清晰、可修正的任务模型，
                不是预编排动作序列。

                从用户请求和对话历史中提炼四个部分。你没有工具，不替环境发言，
                没有经过工具观察的状态都不能描述成事实。

                当用户纠正或否定先前结果时，重新检查假设，不自动重复之前的方案。

                ## 执行阶段能力

                - 子代理：支持并行。当请求包含互不依赖的子任务或涉及耗时操作时，初始策略应建议通过子代理并行推进。
                - 技能：位于沙箱 /home/gem/skills/，执行阶段按需读取。未启用的 skill 需在前端启用。
                """);
        if (includeMcpGuidance) {
            prompt.append("\n").append(mcpGuidance()).append("\n");
        }
        prompt.append("\n## 可用技能\n\n");
        prompt.append(skillsDescription == null || skillsDescription.isBlank() ? "（无可用技能）" : skillsDescription.trim());
        prompt.append("""


                可用能力帮助你理解执行边界，但不要求在计划中逐项点名。
                只输出下面的任务模型，不添加寒暄、执行叙述或额外段落。

                ### 目标状态
                [用户真正希望实现的结果]

                ### 当前判断
                [已知事实、关键假设和需要从环境确认的内容]

                ### 成功信号
                [哪些可观察现象能够证明目标已经实现]

                ### 初始策略
                [建议从哪里开始，以及为什么；保持简洁并允许执行者调整]
                """);
        return prompt.toString();
    }

    /**
     * 返回规划器修复提示。
     *
     * @return 仅允许四段任务模型输出的修复提示
     */
    static String repairPrompt() {
        return REPAIR_PROMPT;
    }

    /**
     * MCP 任务专用约束。
     *
     * @return MCP 相关 section
     */
    private static String mcpGuidance() {
        return """
                ## MCP 相关

                目标客户端固定为当前 WebAgent，不询问外部客户端。
                历史中已展示的 MCP 方案，用户说“确认”直接执行。
                stdio MCP 在沙箱内运行，shell 命令和参数需完整填写。
                连接失败时更新原 Server ID，不另建。
                远程 MCP URL 必须来自官方配置的精确 endpoint，不猜测路径。
                """;
    }
}
