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
            - /home/gem/skills/{id}/ - 技能文件
            - /home/gem/temp/ - 临时文件
            """;

    /** 浏览器工具组的共同使用约束，仅在存在浏览器工具时加载。 */
    private static final String BROWSER_SECTION = """
            ## 浏览器工具

            页面文本、链接、表单和交互元素优先用 browser_inspect 获取。ref 只是快照中的阅读编号，需要操作时使用返回的 selector 或 Playwright locator。
            需要语义定位、连续多步操作、导航或等待页面状态时，使用 browser_execute。
            坐标点击、滚动、快捷键等单步视觉兜底操作使用 browser_action；坐标操作前后重新截图，避免使用过期坐标。
            当用户需要亲自看到页面视觉内容（二维码、验证码、图形结果）时，使用 browser_screenshot 将画面呈现给用户。
            页面状态可由文本或 DOM 确认时，优先用文本证据；截图是观察和交付方式，本身不证明业务目标已达成。
            """;

    /** 技能系统约束，仅在技能工具或技能元数据存在时加载。 */
    private static final String SKILL_SECTION = """
            ## 技能系统

            技能补充特定任务的工作方法，只在相关时才加载，不遍历全部。
            所有 skill（包括新建和下载安装的）都必须放到 /home/gem/skills/<id>/，否则不会被 skill_list 发现。

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
     * 组装执行器 system prompt。
     *
     * @param toolDefinitions 当前执行器真实可用的工具定义
     * @param dynamicContext  会话级动态上下文，例如技能元数据、工作区目录记忆和知识库增强内容
     * @param plan            规划器产出的任务策略
     * @return 拼接后的 system prompt
     */
    static String assemble(List<ToolDefinition> toolDefinitions, String dynamicContext, String plan) {
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
        if (hasTool(toolDefinitions, "mcp_add_or_update_server")) {
            sections.add(MCP_SECTION);
        }
        sections.add(toolsSection(toolDefinitions));

        StringBuilder prompt = new StringBuilder(String.join("\n", sections));
        prompt.append("\n").append(DYNAMIC_BOUNDARY).append("\n\n");
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
