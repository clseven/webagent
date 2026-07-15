package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.llm.LlmToolCall;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话上下文 token 保守估算器。
 *
 * <p>当前实现覆盖正文、reasoning、工具调用参数和协议标识，并通过安全系数降低中文、
 * JSON 与不同模型分词差异造成的低估风险。后续可在不改变调用方的情况下替换为精确 tokenizer。</p>
 */
@Component
public class ConversationContextTokenEstimator {

    /** 上下文配置。 */
    private final AgentConfigProperties.Context properties;

    /**
     * 创建 token 估算器。
     *
     * @param configProperties Agent 配置
     */
    public ConversationContextTokenEstimator(AgentConfigProperties configProperties) {
        this.properties = configProperties.getContext();
    }

    /**
     * 估算消息列表 token。
     *
     * @param messages 模型协议消息
     * @return 含安全余量的估算 token
     */
    public int estimateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long characters = 0;
        for (ChatMessage message : messages) {
            characters += messageCharacters(message);
        }
        return estimateCharacters(characters);
    }

    /**
     * 估算单条文本 token。
     *
     * @param content 文本内容
     * @return 含安全余量的估算 token
     */
    public int estimateText(String content) {
        return estimateCharacters(content != null ? content.length() : 0);
    }

    /**
     * 读取当前上下文配置。
     *
     * @return 上下文配置对象
     */
    public AgentConfigProperties.Context properties() {
        return properties;
    }

    private long messageCharacters(ChatMessage message) {
        long characters = 16;
        characters += length(message.getRole());
        characters += length(message.getContent());
        characters += length(message.getReasoning());
        characters += length(message.getToolCallId());
        for (LlmToolCall toolCall : message.getToolCalls()) {
            characters += length(toolCall.id());
            characters += length(toolCall.name());
            characters += String.valueOf(toolCall.arguments()).length();
        }
        return characters;
    }

    private int estimateCharacters(long characters) {
        double ratio = Math.max(0.5d, properties.getCharsPerToken());
        double safetyRatio = Math.max(1.0d, properties.getTokenSafetyRatio());
        long estimate = (long) Math.ceil(characters / ratio * safetyRatio);
        return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    private int length(String value) {
        return value != null ? value.length() : 0;
    }
}
