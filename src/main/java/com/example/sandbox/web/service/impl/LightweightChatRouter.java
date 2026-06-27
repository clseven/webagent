package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量对话路由器，用于在 Agent 编排前识别确定无需规划模型的社交型输入。
 *
 * <h3>职责</h3>
 * <p>该组件只做保守的本地规则匹配，命中时表示本轮可以跳过 PlanAgent，
 * 但仍应交给 ReactAgent 生成最终回复，避免把执行器链路短路成固定文案。</p>
 */
@Component
public class LightweightChatRouter {

    /**
     * 可直接本地回复的问候短语集合，元素均为规范化后的文本。
     */
    private static final Set<String> GREETING_MESSAGES = Set.of(
            "你好", "您好", "在吗", "在不在",
            "hi", "hello", "hey", "哈喽", "嗨",
            "早上好", "中午好", "下午好", "晚上好"
    );

    /**
     * 可直接本地回复的致谢短语集合，元素均为规范化后的文本。
     */
    private static final Set<String> THANKS_MESSAGES = Set.of(
            "谢谢", "感谢", "多谢", "谢了", "辛苦了"
    );

    /**
     * 可直接本地回复的告别短语集合，元素均为规范化后的文本。
     */
    private static final Set<String> FAREWELL_MESSAGES = Set.of(
            "再见", "拜拜", "bye", "goodbye"
    );

    /**
     * 上下文依赖短语集合，命中时必须交给原有 Agent 链路理解上下文。
     */
    private static final Set<String> CONTEXT_DEPENDENT_MESSAGES = Set.of(
            "可以", "确认", "继续", "好的", "好", "行", "就这个", "开始吧", "安装吧"
    );

    /**
     * 任务意图关键词集合，输入包含这些信号时不得走轻量回复。
     */
    private static final List<String> TASK_KEYWORDS = List.of(
            "帮我", "帮忙", "看一下", "看看", "查看", "分析", "修改", "改一下",
            "写", "生成", "创建", "新建", "搜索", "查找", "查询", "打开",
            "网页", "浏览器", "文件", "代码", "运行", "执行", "命令", "沙箱",
            "sandbox", "mcp", "安装", "接入", "下载", "上传", "解析", "转换",
            "截图", "测试", "修复", "报错", "日志", "项目", "接口", "数据库",
            "知识库", "文档"
    );

    /**
     * 根据用户输入和历史上下文决定是否可以跳过规划阶段。
     *
     * @param userMessage 用户当前输入，允许为空
     * @param history     当前会话历史，用于识别上下文依赖输入
     * @return 可跳过 PlanAgent 时返回 true，否则返回 false
     */
    public boolean shouldSkipPlanning(String userMessage, List<ChatMessage> history) {
        String normalized = normalize(userMessage);
        if (normalized.isBlank()) {
            return false;
        }

        boolean hasHistory = history != null && !history.isEmpty();
        if ((hasHistory && CONTEXT_DEPENDENT_MESSAGES.contains(normalized))
                || containsTaskSignal(normalized)) {
            return false;
        }
        return GREETING_MESSAGES.contains(normalized)
                || THANKS_MESSAGES.contains(normalized)
                || FAREWELL_MESSAGES.contains(normalized);
    }

    /**
     * 将用户输入规范化为适合规则匹配的短文本。
     *
     * @param value 原始用户输入
     * @return 去除空白和标点、转小写后的文本
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lowerCase = value.strip().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder();
        lowerCase.codePoints()
                .filter(codePoint -> !shouldIgnore(codePoint))
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    /**
     * 判断规范化文本中是否包含任务意图信号。
     *
     * @param normalized 已规范化的用户输入
     * @return 包含任务关键词时返回 true
     */
    private boolean containsTaskSignal(String normalized) {
        for (String keyword : TASK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字符是否应在规则匹配前忽略。
     *
     * @param codePoint Unicode 码点
     * @return 空白、标点和符号返回 true
     */
    private boolean shouldIgnore(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }
}
