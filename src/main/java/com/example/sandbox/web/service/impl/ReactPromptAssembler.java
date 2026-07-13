package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行器 system prompt 组装器。
 *
 * <h3>职责</h3>
 * <p>根据当前真实可用工具和动态上下文，按稳定顺序拼接执行器提示词 section。</p>
 *
 * <h3>约束</h3>
 * <p>普通工具调用仍由 {@link ReactAgent} 当前循环逐个处理，因此提示词不得鼓励一次返回多个普通工具调用。</p>
 */
final class ReactPromptAssembler {

    /** 动态段边界，边界前保持稳定以利于上游 prompt cache。 */
    private static final String DYNAMIC_BOUNDARY = "─── 以下为动态段（每会话/每轮可能变化）───";

    /** 执行器身份和核心行为约束，所有场景都加载。 */
    private static final String IDENTITY_SECTION = """
            你是沙箱环境中的执行者。遵循观察-行动-判断循环：
            先看当前状态，再做一步行动，再根据反馈更新判断。

            三条规则：
            1. 事实必须来自工具，不用语言替代观察。每轮只处理一个普通工具调用；收到结果后再决定下一步。需要并行推进多个独立任务时，优先使用 run_subagent。
            2. 工具成功 ≠ 任务完成。回到成功信号找直接证据。
            3. 计划与事实冲突时，改计划；用户否定结果时，改假设。

            沙箱已由系统按会话准备，通过提供的工具访问，不假设宿主机状态。
            支付、发布、删除、发送消息等不可逆操作必须先获得用户明确确认。
            回复时区分已完成、未完成和无法确认，只陈述有证据支持的结论。
            """;

    /** 沙箱路径约定，所有场景都加载。 */
    private static final String WORKSPACE_SECTION = """
            ## 工作空间

            - /home/gem/uploads/ - 用户上传文件
            - /home/gem/workspace/ - 工作目录
            - /home/gem/output/ - 输出结果
            - /home/gem/skills/ - 技能包根目录，系统会递归发现其中的 SKILL.md/skill.md
            - /home/gem/temp/ - 临时文件
            """;

    /** 浏览器工具组的共同使用约束，仅在存在浏览器工具时加载。 */
    private static final String BROWSER_SECTION = """
            ## 浏览器工具

            页面文本、链接、表单和交互元素优先用 browser_inspect 获取。ref 只是快照中的阅读编号，需要操作时使用返回的 selector 或 Playwright locator。
            需要语义定位、连续多步操作、导航或等待页面状态时，使用 browser_execute。
            坐标点击、滚动、快捷键等单步视觉兜底操作使用 browser_action；坐标操作前后重新截图，避免使用过期坐标。
            当用户需要亲自看到页面视觉内容（二维码、验证码、图形结果）时，使用 browser_screenshot 将画面呈现给用户。
            browser_screenshot 的 deliver_to_user 默认 false；过程截图、验证码、拦截页、加载失败页和仅用于观察的截图都保持 false，不进入最终图片卡片组。
            只有用户最终要看的截图才设置 deliver_to_user=true；设置前先确认页面分类、关键词和结果状态符合用户要求。
            用户要求查看后续页面内容或多张截图时，先用 browser_action 滚动后再截图；不要把同一视口或错误分类截图重复交付。
            页面状态可由文本或 DOM 确认时，优先用文本证据；截图是观察和交付方式，本身不证明业务目标已达成。
            需要浏览器搜索网页时，默认使用 Bing（https://www.bing.com）。
            """;

    /** 技能系统约束，仅在技能工具或技能元数据存在时加载。 */
    private static final String SKILL_SECTION = """
            ## 技能系统

            技能补充特定任务的工作方法，只在相关时才加载，不遍历全部。
            新建或下载安装 skill 时，将包含 SKILL.md/skill.md 的真实技能目录放在 /home/gem/skills 下；
            可以是 /home/gem/skills/<id>/，也可以是技能包内部的 /home/gem/skills/<package>/skill/<id>/。
            系统会从当前沙箱自动发现这些技能，skill_list 只用于查看已启用和已发现的结果，不负责触发发现。

            - skill_list：列出可用技能（已启用 + 沙箱发现）
            - skill_activate：激活技能，获取完整指令、scripts 和 references
            - skill_reference：读取技能引用文件（相对路径，禁止 ../）
            """;

    /** 子代理约束，仅在 run_subagent 可用时加载。 */
    private static final String SUBAGENT_SECTION = """
            ## 子代理

            多步骤耗时操作使用 run_subagent 委派给子代理，单次工具调用直接用。
            子代理拥有独立上下文，内部过程不进入主对话，完成后返回任务结果。
            多个子代理可设 run_in_background=true 并行执行。

            适合委派：多步操作、浏览器序列交互、多源网络调研、多个独立任务
            不适合：单次读取、搜索、写入等一步完成的操作
            """;

    /** TodoWrite 运行时任务清单约束，仅在 todo_write 可用时加载。 */
    private static final String TODO_WRITE_SECTION = """
            ## TodoWrite 运行时任务清单

            多步任务开始前使用 todo_write 建立任务清单；轻量问候、单步解释或无需工具的普通回复可以不建清单。
            TodoState 是看板和账本，不调度工具、不替代 ReactAgent 的行动判断。
            todo 只描述目标和验收标准，不描述具体工具微动作。

            状态规则：
            - pending：已列入计划，尚未开始
            - in_progress：当前正在推进；同一时间只保留一个主 in_progress todo
            - completed：必须尽量写入 evidence，证据来自工具 observation、用户确认或可验证输出
            - blocked：必须填写 blocker，说明需要用户、权限、环境还是外部信息
            - cancelled：必须填写 reason，不能静默删除用户明确要求的目标

            工具参数错误、路径错误、schema 错误等局部失败优先修正参数或换等价工具，当前 todo 保持 in_progress。
            发现原假设错误、需要新增前置探测或准备改变路线时，先调用 todo_write 显式更新计划。
            最终回答前确认关键 todo 已 completed 或 blocked；仍有 pending/in_progress 时继续执行或标记合理阻塞。
            """;

    /** 社交轮对话人格，替代 IDENTITY/WORKSPACE/工具段，避免闲聊时自报能力或目录。 */
    private static final String CHAT_PERSONA_SECTION = """
            你是 WebAgent，一个在沙箱环境中协助用户的助手。

            当前是闲聊或简单问答，不需要调用工具，不需要查看工作目录，不需要主动介绍你的能力。
            - 自然、简洁地回应，像正常对话。
            - 用户若提出需要执行的任务（操作文件、运行命令、浏览网页等），你再认真处理，那时会进入任务模式。
            - 不要主动列举能做什么，不要主动描述沙箱目录结构。
            """;

    /** MCP 管理约束，仅在 MCP 管理工具可用时加载。 */
    private static final String MCP_SECTION = """
            ## MCP 动态工具管理

            目标环境固定为当前 WebAgent，不是外部编辑器或桌面客户端。

            安装流程：搜索官方文档 → 确认 transport 和 endpoint → 展示方案等用户确认
            → 安装 → 用 mcp_list_servers 验证连接和工具列表。验证失败不得宣称成功。

            关键约束：
            - URL 必须来自官方配置的精确 endpoint，不猜测路径
            - stdio MCP 在沙箱内运行，shell 命令和参数需完整填写
            - 历史中已展示的方案，用户说“确认”直接执行，不重复搜索
            - MCP 子系统未启用时，提示用户在配置中开启并重启 WebAgent 服务，不自行查找或修改配置文件
            - 连接失败时根据错误码说明原因，修正时更新原 Server ID 不另建
            - 新增 MCP server 工具在下一条用户消息或下一轮 agent loop 才会注册到工具列表，本轮不可调用
            """;

    private ReactPromptAssembler() {
    }

    /**
     * 组装不含运行时快照的执行器 system prompt。
     *
     * @param toolDefinitions 当前执行器真实可用的工具定义
     * @param dynamicContext  会话级动态上下文，例如技能元数据、工作区目录记忆和知识库增强内容
     * @param plan            规划器产出的任务策略
     * @return 拼接后的 system prompt
     */
    static String assemble(List<ToolDefinition> toolDefinitions, String dynamicContext, String plan) {
        return assemble(toolDefinitions, null, dynamicContext, plan);
    }

    /**
     * 组装包含单轮运行时快照的执行器 system prompt。
     *
     * <p>运行时快照位于动态边界之后、其他动态上下文之前，既不污染稳定提示词缓存，
     * 又能让后续技能、工作区记忆和任务策略统一按本轮时间解释。</p>
     *
     * @param toolDefinitions 当前执行器真实可用的工具定义
     * @param runtimeContext  本轮不可变运行时上下文，例如当前日期、时间和时区
     * @param dynamicContext  会话级动态上下文，例如技能元数据、工作区目录记忆和知识库增强内容
     * @param plan            规划器产出的任务策略
     * @return 拼接后的 system prompt
     */
    static String assemble(List<ToolDefinition> toolDefinitions,
                           String runtimeContext,
                           String dynamicContext,
                           String plan) {
        List<String> sections = new ArrayList<>();
        sections.add(IDENTITY_SECTION);
        sections.add(WORKSPACE_SECTION);

        if (hasToolPrefix(toolDefinitions, "browser_")) {
            sections.add(BROWSER_SECTION);
        }
        if (hasSkillSystem(toolDefinitions, dynamicContext)) {
            sections.add(SKILL_SECTION);
        }
        if (hasTool(toolDefinitions, "run_subagent")) {
            sections.add(SUBAGENT_SECTION);
        }
        if (hasTool(toolDefinitions, "todo_write")) {
            sections.add(TODO_WRITE_SECTION);
        }
        if (hasTool(toolDefinitions, "mcp_add_or_update_server")) {
            sections.add(MCP_SECTION);
        }
        sections.add(toolsSection(toolDefinitions));

        StringBuilder prompt = new StringBuilder(String.join("\n", sections));
        prompt.append("\n").append(DYNAMIC_BOUNDARY).append("\n\n");
        if (runtimeContext != null && !runtimeContext.isBlank()) {
            prompt.append(runtimeContext.trim()).append("\n\n");
        }
        if (dynamicContext != null && !dynamicContext.isBlank()) {
            prompt.append(dynamicContext.trim()).append("\n\n");
        }
        if (plan != null && !plan.isBlank()) {
            prompt.append("## 任务策略\n\n");
            prompt.append("以下内容是策略层对任务的当前理解。它提供目标、判断和成功信号，");
            prompt.append("但不是运行时事实，也不是必须照做的步骤清单。");
            prompt.append("执行过程中以最新环境反馈为准，并在必要时修正策略。\n\n");
            prompt.append(plan.trim()).append("\n");
        }
        return prompt.toString();
    }

    /**
     * 组装社交轮 system prompt。
     *
     * <p>社交轮只加载对话人格，跳过执行器身份、工作空间、工具清单和任务策略等所有任务段，
     * 避免闲聊场景被工具定义或工作目录诱导出自报家门的回复。</p>
     *
     * @return 社交轮系统提示词
     */
    static String assembleSocial() {
        return assembleSocial(null);
    }

    /**
     * 组装包含单轮运行时快照的社交轮 system prompt。
     *
     * @param runtimeContext 本轮不可变运行时上下文；为空时只返回社交人格
     * @return 社交人格与运行时上下文组成的系统提示词
     */
    static String assembleSocial(String runtimeContext) {
        if (runtimeContext == null || runtimeContext.isBlank()) {
            return CHAT_PERSONA_SECTION.trim();
        }
        return CHAT_PERSONA_SECTION.trim() + "\n\n" + DYNAMIC_BOUNDARY + "\n\n" + runtimeContext.trim();
    }

    /**
     * 返回本次会加载的 section 名称，用于日志观测。
     *
     * @param toolDefinitions 当前工具定义
     * @param dynamicContext  动态上下文
     * @param plan            任务策略
     * @return section 名称列表
     */
    static List<String> sectionNames(List<ToolDefinition> toolDefinitions, String dynamicContext, String plan) {
        List<String> names = new ArrayList<>();
        names.add("identity");
        names.add("workspace");
        if (hasToolPrefix(toolDefinitions, "browser_")) {
            names.add("browser");
        }
        if (hasSkillSystem(toolDefinitions, dynamicContext)) {
            names.add("skill_system");
        }
        if (hasTool(toolDefinitions, "run_subagent")) {
            names.add("subagent");
        }
        if (hasTool(toolDefinitions, "todo_write")) {
            names.add("todo_write");
        }
        if (hasTool(toolDefinitions, "mcp_add_or_update_server")) {
            names.add("mcp_management");
        }
        names.add("tools");
        names.add("dynamic_boundary");
        if (dynamicContext != null && !dynamicContext.isBlank()) {
            names.add("dynamic_context");
        }
        if (plan != null && !plan.isBlank()) {
            names.add("task_strategy");
        }
        return names;
    }

    /**
     * 构建工具清单段。
     *
     * @param toolDefinitions 当前工具定义
     * @return 可用工具 section
     */
    private static String toolsSection(List<ToolDefinition> toolDefinitions) {
        StringBuilder tools = new StringBuilder("## 可用工具\n\n");
        for (ToolDefinition tool : toolDefinitions) {
            tools.append("- ")
                    .append(tool.getName())
                    .append(": ")
                    .append(tool.getDescription())
                    .append("\n");
        }
        return tools.toString();
    }

    /**
     * 判断是否需要加载技能系统说明。
     *
     * @param toolDefinitions 当前工具定义
     * @param dynamicContext  动态上下文
     * @return true 表示需要技能 section
     */
    private static boolean hasSkillSystem(List<ToolDefinition> toolDefinitions, String dynamicContext) {
        return (dynamicContext != null && !dynamicContext.isBlank() && dynamicContext.contains("技能"))
                || hasToolPrefix(toolDefinitions, "skill_");
    }

    /**
     * 判断指定工具是否存在。
     *
     * @param toolDefinitions 当前工具定义
     * @param name            工具名
     * @return true 表示存在
     */
    private static boolean hasTool(List<ToolDefinition> toolDefinitions, String name) {
        return toolDefinitions.stream().anyMatch(tool -> name.equals(tool.getName()));
    }

    /**
     * 判断是否存在指定前缀的工具。
     *
     * @param toolDefinitions 当前工具定义
     * @param prefix          工具名前缀
     * @return true 表示存在
     */
    private static boolean hasToolPrefix(List<ToolDefinition> toolDefinitions, String prefix) {
        return toolDefinitions.stream().anyMatch(tool -> tool.getName().startsWith(prefix));
    }
}
