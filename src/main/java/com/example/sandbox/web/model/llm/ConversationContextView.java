package com.example.sandbox.web.model.llm;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;

/**
 * 当前轮可直接使用的持久会话上下文。
 *
 * @param summary 长期摘要正文
 * @param recentMessages 最近完整模型协议
 */
public record ConversationContextView(String summary, List<ChatMessage> recentMessages) {

    /** 创建不含摘要和协议消息的视图。 */
    public static ConversationContextView empty() {
        return new ConversationContextView("", List.of());
    }
}
