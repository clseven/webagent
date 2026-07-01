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
        if (isSearchTool(normalizedTool)) {
            return describeSearch(arguments, plan);
        }
        if ("run_subagent".equals(normalizedTool)) {
            return "准备启动子代理处理当前任务中的独立部分。";
        }
        return "准备调用 " + normalizedTool + " 工具继续处理当前任务。";
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
            return "准备搜索公开信息，" + scope;
        }
        return "准备搜索 " + query + "，" + scope;
    }

    /**
     * 从工具参数中提取搜索词。
     *
     * @param arguments 工具参数
     * @return 搜索词，缺失时返回空字符串
     */
    private static String extractQuery(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        for (String key : QUERY_KEYS) {
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
