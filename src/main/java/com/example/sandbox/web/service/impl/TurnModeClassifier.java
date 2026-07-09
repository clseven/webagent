package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轮次模式分类器，将用户输入归类为 SOCIAL / TASK / AMBIGUOUS。
 *
 * <p>由 {@link TurnPolicyResolver} 调用，产出 {@link TurnMode} 后再映射为 {@link TurnPolicy}。</p>
 */
@Component
public class TurnModeClassifier {

    private static final Set<String> GREETING_MESSAGES = Set.of(
            "你好", "您好", "在吗", "在不在",
            "hi", "hello", "hey", "哈喽", "嗨",
            "早上好", "中午好", "下午好", "晚上好"
    );

    private static final Set<String> THANKS_MESSAGES = Set.of(
            "谢谢", "感谢", "多谢", "谢了", "辛苦了"
    );

    private static final Set<String> FAREWELL_MESSAGES = Set.of(
            "再见", "拜拜", "bye", "goodbye"
    );

    private static final Set<String> CONTEXT_DEPENDENT_MESSAGES = Set.of(
            "可以", "确认", "继续", "好的", "好", "行", "就这个", "开始吧", "安装吧"
    );

    private static final List<String> TASK_KEYWORDS = List.of(
            "帮我", "帮忙", "看一下", "看看", "查看", "分析", "修改", "改一下",
            "写", "生成", "创建", "新建", "搜索", "查找", "查询", "打开",
            "网页", "浏览器", "文件", "代码", "运行", "执行", "命令", "沙箱",
            "sandbox", "mcp", "安装", "接入", "下载", "上传", "解析", "转换",
            "截图", "测试", "修复", "报错", "日志", "项目", "接口", "数据库",
            "知识库", "文档"
    );

    /**
     * 将用户输入归类为轮次模式。
     *
     * @param userMessage 用户当前输入，允许为空
     * @param history     当前会话历史，用于识别上下文依赖输入
     * @return 轮次分类
     */
    public TurnMode classify(String userMessage, List<ChatMessage> history) {
        String normalized = normalize(userMessage);
        if (normalized.isBlank()) {
            return TurnMode.AMBIGUOUS;
        }

        boolean hasHistory = history != null && !history.isEmpty();

        if (hasHistory && CONTEXT_DEPENDENT_MESSAGES.contains(normalized)) {
            return TurnMode.TASK;
        }

        if (containsTaskSignal(normalized)) {
            return TurnMode.TASK;
        }

        if (GREETING_MESSAGES.contains(normalized)
                || THANKS_MESSAGES.contains(normalized)
                || FAREWELL_MESSAGES.contains(normalized)) {
            return TurnMode.SOCIAL;
        }

        return TurnMode.AMBIGUOUS;
    }

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

    private boolean containsTaskSignal(String normalized) {
        for (String keyword : TASK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

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
