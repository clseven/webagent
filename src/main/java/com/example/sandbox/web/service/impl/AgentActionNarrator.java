package com.example.sandbox.web.service.impl;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生成工具调用前展示给用户的公开行动说明。
 *
 * <p>该类只输出可审计的行动摘要，不生成或暴露模型的完整思考链。
 * 说明内容基于工具名称、工具参数和执行计划文本确定，用于让用户理解
 * Agent 为什么即将调用某个工具。</p>
 */
public final class AgentActionNarrator {

    /** 搜索类工具常用的查询参数名。 */
    private static final List<String> QUERY_KEYS = List.of("query", "q", "keyword", "keywords");

    private AgentActionNarrator() {
    }

    /**
     * 根据工具调用生成简短行动说明。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数，可为空
     * @param plan 执行计划文本，可为空
     * @return 面向用户展示的行动说明
     */
    public static String describe(String toolName, Map<String, Object> arguments, String plan) {
        String normalizedTool = toolName != null ? toolName : "unknown";
        return switch (normalizedTool) {
            case "browser_inspect" -> withTarget("正在检查网页内容", extractTarget(arguments));
            case "browser_execute" -> describeBrowserExecute(arguments);
            case "browser_screenshot" -> withTarget("正在保存页面截图", extractTarget(arguments));
            case "browser_action" -> describeBrowserAction(arguments);
            case "browser_info" -> "正在确认浏览器页面状态";
            case "execute_command" -> withTarget("正在运行本地检查", extractCommand(arguments));
            case "shell_wait" -> "正在等待命令执行结果";
            case "shell_kill" -> "正在停止运行中的命令";
            case "read_file" -> withTarget("正在读取文件", extractPath(arguments));
            case "list_files" -> withTarget("正在查看文件列表", extractPath(arguments));
            case "file_search" -> withTarget("正在搜索项目文件", extractQuery(arguments));
            case "file_replace", "str_replace_editor" -> withTarget("正在修改文件", extractPath(arguments));
            case "write_file" -> withTarget("正在写入文件", extractPath(arguments));
            case "download_file" -> withTarget("正在准备文件下载", extractPath(arguments));
            case "parse_document" -> withTarget("正在解析文档", extractPath(arguments));
            case "convert_to_markdown" -> withTarget("正在转换文档为 Markdown", extractPath(arguments));
            case "view_image" -> withTarget("正在查看图片", extractPath(arguments));
            case "todo_write" -> describeTodoWrite(arguments);
            case "run_subagent" -> "正在启动子任务";
            case "request_sandbox" -> "正在准备沙箱环境";
            case "skill_list" -> "正在查看可用技能";
            case "skill_activate" -> withTarget("正在启用技能", extractAny(arguments, "skill", "name", "skillName"));
            case "skill_reference" -> withTarget("正在读取技能参考", extractAny(arguments, "skill", "name", "reference"));
            case "mcp_list_servers" -> "正在查看 MCP 服务";
            case "mcp_add_or_update_server" -> withTarget("正在更新 MCP 服务", extractAny(arguments, "name", "serverName"));
            case "mcp_remove_server" -> withTarget("正在移除 MCP 服务", extractAny(arguments, "name", "serverName"));
            case "mcp_reload" -> "正在刷新 MCP 配置";
            default -> isSearchTool(normalizedTool) ? describeSearch(arguments, plan) : "正在处理当前任务";
        };
    }

    /**
     * 生成浏览器脚本执行的行动说明。
     *
     * @param arguments 工具参数
     * @return 浏览器脚本执行说明
     */
    private static String describeBrowserExecute(Map<String, Object> arguments) {
        String comment = extractLeadingCodeComment(arguments);
        if (!comment.isBlank()) {
            return withTarget("正在" + normalizeCodeComment(comment), extractTarget(arguments));
        }
        return withTarget("正在提取页面信息", extractTarget(arguments));
    }

    /**
     * 生成浏览器动作的行动说明。
     *
     * @param arguments 工具参数
     * @return 浏览器动作说明
     */
    private static String describeBrowserAction(Map<String, Object> arguments) {
        String action = extractAny(arguments, "action_type");
        if (action.isBlank()) {
            return "正在操作浏览器";
        }
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "HOTKEY", "PRESS" -> withTarget("正在发送按键", extractAny(arguments, "key", "keys"));
            case "TYPING" -> "正在输入文本";
            case "CLICK", "DOUBLE_CLICK", "RIGHT_CLICK" -> "正在点击页面";
            case "SCROLL" -> "正在滚动页面";
            case "WAIT" -> "正在等待页面响应";
            case "MOVE_TO" -> "正在移动鼠标";
            default -> "正在操作浏览器";
        };
    }

    /**
     * 生成 todo_write 的行动说明。
     *
     * @param arguments 工具参数
     * @return 任务清单更新说明
     */
    private static String describeTodoWrite(Map<String, Object> arguments) {
        Object rawTodos = arguments != null ? arguments.get("todos") : null;
        if (rawTodos instanceof List<?> todos && !todos.isEmpty()) {
            return "正在更新任务进度（" + todos.size() + " 项）";
        }
        return "正在更新任务进度";
    }

    /**
     * 判断是否属于搜索类工具。
     *
     * @param toolName 工具名称
     * @return true 表示搜索类工具
     */
    private static boolean isSearchTool(String toolName) {
        String lower = toolName.toLowerCase(Locale.ROOT);
        return lower.contains("search");
    }

    /**
     * 生成搜索类工具的行动说明。
     *
     * @param arguments 工具参数
     * @param plan 执行计划文本
     * @return 搜索行动说明
     */
    private static String describeSearch(Map<String, Object> arguments, String plan) {
        String query = extractQuery(arguments);
        String scope = mentionsOfficialSource(plan) || mentionsOfficialSource(query)
                ? "优先确认官方来源。"
                : "验证当前任务需要的信息。";
        if (query.isBlank()) {
            return "正在搜索公开信息，" + scope;
        }
        return "正在搜索 " + query + "，" + scope;
    }

    /**
     * 从工具参数中提取搜索词。
     *
     * @param arguments 工具参数
     * @return 搜索词，缺失时返回空字符串
     */
    private static String extractQuery(Map<String, Object> arguments) {
        String direct = extractAny(arguments, QUERY_KEYS.toArray(String[]::new));
        if (!direct.isBlank()) {
            return direct;
        }
        return "";
    }

    /**
     * 从工具参数中提取文件路径。
     *
     * @param arguments 工具参数
     * @return 文件路径，缺失时返回空字符串
     */
    private static String extractPath(Map<String, Object> arguments) {
        return extractAny(arguments, "path", "file_path", "filePath", "target_file", "targetFile",
                "source_path", "sourcePath", "relativePath");
    }

    /**
     * 从工具参数中提取命令摘要。
     *
     * @param arguments 工具参数
     * @return 命令摘要，缺失时返回空字符串
     */
    private static String extractCommand(Map<String, Object> arguments) {
        return extractAny(arguments, "command", "cmd");
    }

    /**
     * 从工具参数中提取 URL 或页面目标。
     *
     * @param arguments 工具参数
     * @return 页面目标，缺失时返回空字符串
     */
    private static String extractTarget(Map<String, Object> arguments) {
        return extractAny(arguments, "url", "href", "target", "selector");
    }

    /**
     * 从代码参数中提取第一行注释。
     *
     * @param arguments 工具参数
     * @return 注释内容，缺失时返回空字符串
     */
    private static String extractLeadingCodeComment(Map<String, Object> arguments) {
        String code = extractAny(arguments, "code", "script");
        if (code.isBlank()) {
            return "";
        }
        for (String line : code.split("\\R", 8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) {
                return abbreviate(trimmed.substring(2).trim(), 80);
            }
            if (trimmed.startsWith("/*") && trimmed.endsWith("*/")) {
                return abbreviate(trimmed.substring(2, trimmed.length() - 2).trim(), 80);
            }
            if (!trimmed.isBlank()) {
                return "";
            }
        }
        return "";
    }

    /**
     * 将英文代码注释归一成较自然的中文动作。
     *
     * @param comment 代码注释
     * @return 可拼接在“正在”后的动作文本
     */
    private static String normalizeCodeComment(String comment) {
        String lower = comment.toLowerCase(Locale.ROOT);
        if (lower.contains("stats") || lower.contains("stars") || lower.contains("forks")) {
            return "读取页面统计信息";
        }
        if (lower.contains("extract") || lower.contains("get ")) {
            return "提取页面信息";
        }
        if (lower.contains("click")) {
            return "操作页面";
        }
        if (lower.contains("url")) {
            return "确认页面地址";
        }
        return abbreviate(comment, 60);
    }

    /**
     * 为行动说明追加目标信息。
     *
     * @param action 行动说明
     * @param target 目标文本
     * @return 带目标的行动说明
     */
    private static String withTarget(String action, String target) {
        if (target == null || target.isBlank()) {
            return action;
        }
        return action + " " + abbreviate(target.trim(), 80);
    }

    /**
     * 从参数中按候选键提取短文本。
     *
     * @param arguments 工具参数
     * @param keys 候选参数名
     * @return 参数文本，缺失时返回空字符串
     */
    private static String extractAny(Map<String, Object> arguments, String... keys) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return abbreviate(text, 120);
                }
            }
        }
        return "";
    }

    /**
     * 判断文本是否提到官方来源。
     *
     * @param value 待检查文本
     * @return true 表示文本中含有官方来源线索
     */
    private static boolean mentionsOfficialSource(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("official")
                || lower.contains("github")
                || lower.contains("官网")
                || lower.contains("官方");
    }

    /**
     * 限制展示文本长度，避免长查询撑开执行轨迹。
     *
     * @param value 原始文本
     * @param maxLength 最大展示长度
     * @return 截断后的文本
     */
    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
