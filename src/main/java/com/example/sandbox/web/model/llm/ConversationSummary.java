package com.example.sandbox.web.model.llm;

/**
 * 可持久化的会话长期摘要。
 *
 * @param version 摘要结构版本
 * @param content 模型可读的任务摘要正文
 */
public record ConversationSummary(int version, String content) {

    /** 当前摘要结构版本。 */
    public static final int CURRENT_VERSION = 1;

    /**
     * 创建当前版本摘要。
     *
     * @param content 摘要正文，可为空
     * @return 当前版本摘要
     */
    public static ConversationSummary of(String content) {
        return new ConversationSummary(CURRENT_VERSION, content != null ? content : "");
    }
}
