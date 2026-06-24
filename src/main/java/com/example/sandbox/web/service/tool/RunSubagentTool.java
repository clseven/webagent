package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.config.SubAgentConfigProperties;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.AgentResponse;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.ReactAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子代理工具 — 让 LLM 能将复杂子任务委托给独立的子 Agent 执行。
 *
 * <h3>设计原则</h3>
 * <p>子代理 = 标准工具。遵循和 {@code read_file}、{@code execute_command}
 * 等工具完全一致的调用路径，ReactAgent 循环不需任何修改。</p>
 *
 * <h3>上下文隔离</h3>
 * <p>子代理拥有独立的 {@code messages[]}，其内部工具调用的原始输出
 * 不会进入主会话上下文。子代理完成时只返回结构化摘要。</p>
 *
 * <h3>安全策略不跳过</h3>
 * <p>子代理从父 ReactAgent fork，继承 PreToolUse/PostToolUse Hook。</p>
 *
 * @author example
 * @date 2026/06/23
 */
@Component
public class RunSubagentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RunSubagentTool.class);

    private static final String NAME = "run_subagent";

    /** 父 Agent 引用（由 AgentServiceImpl 在创建 ReactAgent 后注入） */
    private ReactAgent parentAgent;

    @Autowired
    private SubAgentConfigProperties config;

    @Autowired
    private com.example.sandbox.web.service.impl.BackgroundTaskManager backgroundTaskManager;

    // ==================== 各类型子代理的系统提示词 ====================

    private static final String ANALYZER_PROMPT = """
            你是数据分析专家，在隔离的上下文中独立分析文件和数据。

            ## 你的任务
            完成指定的分析任务，返回结构化摘要。

            ## 重要规则
            1. 直接执行任务，不要询问用户问题
            2. 执行完成后，按以下格式输出摘要：

            ## 分析摘要
            ### 数据概况
            - 分析了哪些文件/数据
            - 数据规模

            ### 关键发现
            1. 发现一
            2. 发现二

            ### 问题列表（如有）
            - 问题及位置

            ### 建议
            - 后续操作建议

            3. 不要输出原始文件内容——只输出分析结论
            4. 整体输出保持在 500 字以内
            5. 如果任务无法完成，如实说明原因
            """;

    private static final String SEARCHER_PROMPT = """
            你是知识库检索专家，在隔离的上下文中独立检索知识库。

            ## 你的任务
            根据指定的查询，检索知识库中的相关内容，返回与查询最相关的摘要。

            ## 重要规则
            1. 直接执行检索，不要询问用户问题
            2. 检索完成后，按以下格式输出摘要：

            ## 检索摘要
            ### 查询分析
            - 核心问题
            - 使用的检索关键词

            ### 相关内容
            1. **来源**: xxx | **相关性**: 高/中/低
               **内容**: 相关摘要

            ### 综合回答
            基于检索结果，对查询的回答

            3. 只返回与查询高度相关的内容
            4. 整体输出保持在 500 字以内
            5. 标注来源便于追溯
            """;

    private static final String BROWSER_PROMPT = """
            你是浏览器操作专家，在隔离的上下文中独立执行网页操作。

            ## 你的任务
            执行指定的浏览器操作（导航、截图、抓取内容等），返回操作结果摘要。

            ## 重要规则
            1. 直接执行操作，不要询问用户问题
            2. 截图会自动保存，你只需要在摘要中说明截图内容
            3. 执行完成后，按以下格式输出摘要：

            ## 操作摘要
            ### 执行的操作
            - 访问的页面
            - 执行的动作

            ### 关键结果
            - 页面信息
            - 抓取内容摘要

            4. 不要输出原始 HTML 或大段文本
            5. 整体输出保持在 500 字以内
            """;

    private static final String GENERAL_PROMPT = """
            你是通用任务执行者，在隔离的上下文中独立完成指定任务。

            ## 你的任务
            完成指定的任务，返回结构化摘要。

            ## 重要规则
            1. 直接执行任务，不要询问用户问题
            2. 执行完成后，按以下格式输出摘要：

            ## 执行摘要
            ### 完成的操作
            1. 操作一
            2. 操作二

            ### 关键结果
            - 结果描述

            ### 产出文件（如有）
            - 文件路径和说明

            ### 后续建议（如有）
            - 用户可继续的操作

            3. 不要输出工具调用的原始数据——只输出摘要
            4. 整体输出保持在 500 字以内
            5. 如果任务无法完成，如实说明原因
            """;

    // ==================== 各类型子代理的工具白名单 ====================

    private static final Set<String> ANALYZER_TOOLS = Set.of(
            "read_file", "list_files", "file_search", "execute_command",
            "write_file", "str_replace_editor", "file_replace",
            "download_file", "convert_to_markdown", "document_parser"
    );

    private static final Set<String> SEARCHER_TOOLS = Set.of(
            "knowledge_search"
    );

    private static final Set<String> BROWSER_TOOLS = Set.of(
            "browser_action", "browser_screenshot", "browser_info",
            "browser_execute", "browser_inspect"
    );

    // ==================== Tool 接口实现 ====================

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", Map.of(
                "type", "string",
                "enum", List.of("analyzer", "searcher", "browser", "general"),
                "description", "子代理类型。analyzer=分析文件/数据(读取+分析)，searcher=检索知识库，browser=浏览器操作，general=通用(不限工具)"
        ));
        properties.put("task", Map.of(
                "type", "string",
                "description", "详细的任务描述。子代理将独立完成此任务，完成后返回摘要。描述中应包含：目标、需要处理的文件/路径、期望的结果。"
        ));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "设为 true 时子代理在后台执行，主 Agent 不等待。多个后台子代理可以并行执行。完成后以通知形式告知摘要。"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("type", "task")
        );

        return new ToolDefinition(
                NAME,
                "启动一个子代理来独立处理复杂、多步骤的子任务。子代理拥有独立的上下文，"
                        + "完成后只返回摘要，不会用中间过程污染主对话。"
                        + "适用场景：(1) 任务涉及多次工具调用，(2) 子任务是自包含的、可以独立完成，"
                        + "(3) 需要分析或处理大量数据但只需结论。"
                        + "不适用场景：简单的单工具操作（直接调用工具即可）。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        if (parentAgent == null) {
            return "错误：子代理工具未初始化（parentAgent 未设置）";
        }

        String type = (String) arguments.getOrDefault("type", "general");
        String task = (String) arguments.get("task");
        if (task == null || task.isBlank()) {
            return "错误：task 参数不能为空";
        }

        // 1. 检查配置：该类型是否启用
        SubAgentConfigProperties.TypeConfig typeConfig = config.getTypeConfig(type);
        if (typeConfig == null) {
            return String.format("错误：子代理类型 '%s' 未启用或不存在。可用类型：analyzer, searcher, browser, general", type);
        }

        // 2. 获取受限工具列表和系统提示词
        List<Tool> restrictedTools = getRestrictedTools(type);
        String subagentPrompt = getSubagentPrompt(type);

        log.info("启动子代理: type={}, task={}, tools={}",
                type, task.length() > 100 ? task.substring(0, 100) + "..." : task,
                restrictedTools.stream().map(t -> t.getDefinition().getName()).toList());

        // 3. Fork 子 ReactAgent
        ReactAgent child = parentAgent.fork(restrictedTools, subagentPrompt);

        // 4. 后台执行：闭包捕获 child，无 singleton 风险
        boolean isBackground = Boolean.TRUE.equals(arguments.get("run_in_background"));
        if (isBackground) {
            String bgId = backgroundTaskManager.start(sessionId, () -> {
                long timeout = config.getTimeoutSeconds();
                try {
                    AgentResponse r = CompletableFuture
                            .supplyAsync(() -> child.run(sessionId, task, List.of()))
                            .orTimeout(timeout, TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                String errMsg = ex instanceof TimeoutException
                                        ? "子代理执行超时（" + timeout + "秒）"
                                        : "子代理执行失败：" + ex.getMessage();
                                return new AgentResponse(errMsg, null, List.of(), null, 0);
                            })
                            .join();
                    return r.getFinalAnswer() != null ? r.getFinalAnswer() : "子代理未返回有效结果";
                } catch (Exception e) {
                    return "子代理执行异常：" + e.getMessage();
                }
            }, "run_subagent:" + type);
            return String.format("[后台子代理 %s 已启动] 类型=%s。完成后将通知结果。", bgId, type);
        }

        // 5. 同步执行（带超时）
        long timeoutSeconds = config.getTimeoutSeconds();
        try {
            AgentResponse response = CompletableFuture
                    .supplyAsync(() -> child.run(sessionId, task, List.of()))
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            log.warn("子代理执行超时 ({}s): type={}", timeoutSeconds, type);
                            return new AgentResponse(
                                    "子代理执行超时（" + timeoutSeconds + "秒），请简化任务或缩小范围",
                                    null, List.of(), null, 0);
                        }
                        log.error("子代理执行失败: type={}", type, ex);
                        return new AgentResponse(
                                "子代理执行失败：" + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()),
                                null, List.of(), null, 0);
                    })
                    .join();

            log.info("子代理完成: type={}, 迭代={}, 输出长度={}",
                    type, response.getIterations(),
                    response.getFinalAnswer() != null ? response.getFinalAnswer().length() : 0);

            return response.getFinalAnswer() != null
                    ? response.getFinalAnswer()
                    : "子代理未返回有效结果";

        } catch (Exception e) {
            log.error("子代理执行异常: type={}", type, e);
            return "子代理执行异常：" + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 设置父 Agent 引用（由 AgentServiceImpl 在构造 ReactAgent 后调用）。
     */
    public void setParentAgent(ReactAgent parentAgent) {
        this.parentAgent = parentAgent;
    }

    /**
     * 清除父 Agent 引用，仅在测试或清理时使用。
     */
    public void clearParentAgent() {
        this.parentAgent = null;
    }

    /**
     * 根据类型获取受限工具列表。
     *
     * <p>工具从父 Agent 的工具集中按名称过滤。父 Agent 的工具实例
     * 已经包含了正确的 sessionId 绑定。</p>
     */
    private List<Tool> getRestrictedTools(String type) {
        Set<String> allowedNames = getAllowedToolNames(type);

        List<Tool> allTools = parentAgent.getTools();
        List<Tool> filtered = new ArrayList<>();
        for (Tool tool : allTools) {
            String name = tool.getDefinition().getName();
            // 永远排除 run_subagent 自身（禁止递归创建子代理）
            if (NAME.equals(name)) {
                continue;
            }
            // general 类型：包含所有工具（除 run_subagent）
            if (allowedNames == null || allowedNames.contains(name)) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    /**
     * 根据类型返回允许的工具名称集合。
     */
    private Set<String> getAllowedToolNames(String type) {
        return switch (type) {
            case "analyzer" -> ANALYZER_TOOLS;
            case "searcher" -> SEARCHER_TOOLS;
            case "browser" -> BROWSER_TOOLS;
            case "general" -> null; // null 表示"除 run_subagent 外的所有工具"
            default -> ANALYZER_TOOLS; // 未知类型默认 analyzer
        };
    }

    /**
     * 根据类型返回子代理系统提示词。
     */
    private String getSubagentPrompt(String type) {
        return switch (type) {
            case "analyzer" -> ANALYZER_PROMPT;
            case "searcher" -> SEARCHER_PROMPT;
            case "browser" -> BROWSER_PROMPT;
            case "general" -> GENERAL_PROMPT;
            default -> GENERAL_PROMPT;
        };
    }
}
