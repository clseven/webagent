package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.Tool;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据本轮任务边界约束搜索工具的 PreToolUse Hook。
 *
 * <p>该 Hook 只管理搜索范围和搜索预算：精确目标任务会围绕目标收敛搜索，
 * 多问题任务会按问题数量放宽预算，搜索预算耗尽时要求模型总结已知结果并询问用户。
 * 它不处理安装、删除、写文件等权限确认。</p>
 */
public class AgentSearchPolicyHook implements ReactAgent.PreToolUseHook {

    /** 搜索查询常见参数名。 */
    private static final List<String> QUERY_KEYS = List.of("query", "q", "keyword", "keywords");

    /** 用户消息中可作为精确目标的英文/数字标识。 */
    private static final Pattern TARGET_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9._/-]{2,}");

    /** 不应被当作精确目标的常见泛词。 */
    private static final Set<String> GENERIC_TOKENS = Set.of(
            "mcp", "server", "tool", "tools", "agent", "search", "github", "official",
            "npm", "docs", "doc", "java", "code"
    );

    /** 本轮用户原始请求。 */
    private final String userMessage;

    /** PlanAgent 生成的计划文本。 */
    private final String plan;

    /** 本轮搜索模式。 */
    private final SearchMode mode;

    /** 精确目标名，非精确模式时为空字符串。 */
    private final String target;

    /** 本轮最多允许执行的搜索次数。 */
    private final int maxSearches;

    /** 已放行的搜索查询，用于去重和计数。 */
    private final Set<String> allowedQueries = new HashSet<>();

    /**
     * 创建搜索策略 Hook。
     *
     * @param userMessage 本轮用户原始请求，可为空
     * @param plan PlanAgent 生成的计划文本，可为空
     */
    public AgentSearchPolicyHook(String userMessage, String plan) {
        this.userMessage = userMessage != null ? userMessage : "";
        this.plan = plan != null ? plan : "";
        this.target = detectExactTarget(this.userMessage, this.plan);
        this.mode = detectMode(this.userMessage, this.plan, this.target);
        this.maxSearches = determineBudget(this.userMessage, this.mode);
    }

    /**
     * 工具调用前检查搜索范围和预算。
     *
     * @param toolCall 工具调用
     * @param sessionId 会话 ID
     * @param tools 当前可用工具表
     * @return null 表示放行；非 null 表示阻止原因，会作为 observation 交回模型
     */
    @Override
    public String run(LlmToolCall toolCall, String sessionId, Map<String, Tool> tools) {
        if (toolCall == null || !isInternetSearchTool(toolCall.name())) {
            return null;
        }
        String query = extractQuery(toolCall.arguments());
        if (query.isBlank()) {
            return null;
        }
        if (mode == SearchMode.EXACT_TARGET && !queryMentionsTarget(query, target)) {
            return "当前任务只要求确认「" + target + "」。搜索词「" + query
                    + "」已经偏离目标，请不要继续搜索替代方案；请基于已有结果总结，并询问用户是否要扩大搜索范围。";
        }
        if (allowedQueries.size() >= maxSearches && !allowedQueries.contains(normalize(query))) {
            return "本轮搜索预算已用完（已搜索 " + allowedQueries.size() + " 次）。请停止继续搜索，"
                    + "总结已知结果，并询问用户是否继续深入或扩大搜索范围。";
        }
        allowedQueries.add(normalize(query));
        return null;
    }

    /**
     * 判断工具是否属于互联网搜索类工具。
     *
     * @param toolName 工具名称
     * @return true 表示应由本策略约束
     */
    private boolean isInternetSearchTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String lower = toolName.toLowerCase(Locale.ROOT);
        if (lower.contains("knowledge") || lower.contains("vector")) {
            return false;
        }
        return "web_search".equals(lower)
                || lower.contains("web_search")
                || lower.contains("brave_search")
                || lower.contains("tavily")
                || lower.contains("serper")
                || lower.contains("internet_search")
                || (lower.contains("search") && !lower.contains("file"));
    }

    /**
     * 提取工具调用中的搜索词。
     *
     * @param arguments 工具参数
     * @return 搜索词，缺失时为空字符串
     */
    private String extractQuery(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        for (String key : QUERY_KEYS) {
            Object value = arguments.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * 判断查询是否仍围绕精确目标。
     *
     * @param query 搜索词
     * @param target 精确目标
     * @return true 表示搜索词包含目标
     */
    private boolean queryMentionsTarget(String query, String target) {
        return !target.isBlank() && normalize(query).contains(normalize(target));
    }

    /**
     * 判断本轮搜索模式。
     *
     * @param userMessage 用户请求
     * @param plan 计划文本
     * @param target 精确目标
     * @return 搜索模式
     */
    private static SearchMode detectMode(String userMessage, String plan, String target) {
        if (!target.isBlank() && containsAny(userMessage, "下载", "安装", "接入", "找一个", "装一下", "加一下")) {
            return SearchMode.EXACT_TARGET;
        }
        if (countQuestions(userMessage) >= 2) {
            return SearchMode.MULTI_QUESTION;
        }
        String joined = (userMessage + "\n" + plan).toLowerCase(Locale.ROOT);
        if (joined.contains("对比") || joined.contains("比较") || joined.contains("推荐")
                || joined.contains("best") || joined.contains("compare")) {
            return SearchMode.OPEN_RESEARCH;
        }
        return SearchMode.DEFAULT;
    }

    /**
     * 识别精确目标名称。
     *
     * @param userMessage 用户请求
     * @param plan 计划文本
     * @return 目标名称；未识别时为空字符串
     */
    private static String detectExactTarget(String userMessage, String plan) {
        String text = userMessage != null && !userMessage.isBlank() ? userMessage : plan;
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = TARGET_TOKEN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!GENERIC_TOKENS.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * 根据搜索模式确定预算。
     *
     * @param userMessage 用户请求
     * @param mode 搜索模式
     * @return 最多允许搜索次数
     */
    private static int determineBudget(String userMessage, SearchMode mode) {
        return switch (mode) {
            case EXACT_TARGET -> 2;
            case MULTI_QUESTION -> Math.min(12, Math.max(4, countQuestions(userMessage) * 2));
            case OPEN_RESEARCH -> 6;
            case DEFAULT -> 5;
        };
    }

    /**
     * 统计用户请求中包含的问题数量。
     *
     * @param text 用户请求
     * @return 问题数量
     */
    private static int countQuestions(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '?' || c == '？') {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断文本是否包含任一关键词。
     *
     * @param text 文本
     * @param keywords 关键词列表
     * @return true 表示至少命中一个关键词
     */
    private static boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化文本便于比较。
     *
     * @param value 原始文本
     * @return 小写、压缩空白后的文本
     */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * 搜索任务模式。
     */
    private enum SearchMode {
        /** 精确目标，如“下载 agentsearch”。 */
        EXACT_TARGET,
        /** 多问题检索，每个问题应至少有搜索空间。 */
        MULTI_QUESTION,
        /** 开放研究或横向对比。 */
        OPEN_RESEARCH,
        /** 普通检索。 */
        DEFAULT
    }
}
