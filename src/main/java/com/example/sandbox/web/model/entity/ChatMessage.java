package com.example.sandbox.web.model.entity;

import com.example.sandbox.web.model.llm.LlmToolCall;

import java.time.Instant;
import java.util.List;

/**
 * 对话消息
 *
 * @author example
 * @date 2026/05/14
 */
public class ChatMessage {

    /**
     * 消息角色
     */
    private final String role;

    /**
     * 消息内容
     */
    private final String content;

    /**
     * 思考链内容（reasoning_content，部分模型支持）
     */
    private final String reasoning;

    /**
     * 时间戳（毫秒）
     */
    private final Long timestamp;

    /**
     * 文件附件列表（agent 回复时附带文件）
     */
    private final List<FileAttachment> files;

    /**
     * 工具调用 ID（role=tool 时关联对应的 tool_call）
     */
    private final String toolCallId;

    /**
     * 助手发起的工具调用（role=assistant 时使用）
     */
    private final List<LlmToolCall> toolCalls;

    private ChatMessage(String role, String content, String reasoning, Long timestamp,
                        List<FileAttachment> files, String toolCallId, List<LlmToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.timestamp = timestamp;
        this.files = files != null ? List.copyOf(files) : List.of();
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }

    /**
     * 从持久化数据恢复聊天消息。
     *
     * <p>当前数据库消息不保存附件和工具调用协议字段，因此恢复时使用空值。</p>
     */
    public static ChatMessage restore(String role, String content, String reasoning, Long timestamp) {
        return new ChatMessage(role, content, reasoning, timestamp, null, null, null);
    }

    /**
     * 创建用户消息
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content, null, Instant.now().toEpochMilli(), null, null, null);
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage("assistant", content, null, Instant.now().toEpochMilli(), null, null, null);
    }

    /**
     * 创建助手消息（带思考链）
     */
    public static ChatMessage assistantMessage(String content, String reasoning) {
        return new ChatMessage("assistant", content, reasoning, Instant.now().toEpochMilli(), null, null, null);
    }

    /**
     * 创建助手消息（带文件附件）
     */
    public static ChatMessage assistantMessage(String content, List<FileAttachment> files) {
        return new ChatMessage("assistant", content, null, Instant.now().toEpochMilli(), files, null, null);
    }

    /**
     * 创建助手消息（带思考链和文件附件）
     */
    public static ChatMessage assistantMessage(String content, String reasoning, List<FileAttachment> files) {
        return new ChatMessage("assistant", content, reasoning, Instant.now().toEpochMilli(), files, null, null);
    }

    /**
     * 创建助手工具调用消息（原生 tool calling 协议）
     */
    public static ChatMessage assistantToolCallMessage(LlmToolCall toolCall) {
        return new ChatMessage("assistant", null, null, Instant.now().toEpochMilli(),
                null, null, List.of(toolCall));
    }

    /**
     * 创建系统消息
     */
    public static ChatMessage systemMessage(String content) {
        return new ChatMessage("system", content, null, Instant.now().toEpochMilli(), null, null, null);
    }

    /**
     * 创建工具结果消息（原生 tool calling 协议）
     *
     * @param toolCallId 工具调用 ID，关联 assistant 消息中的 tool_calls[].id
     * @param content    工具执行结果
     */
    public static ChatMessage toolMessage(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, Instant.now().toEpochMilli(), null, toolCallId, null);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getReasoning() {
        return reasoning;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public List<FileAttachment> getFiles() {
        return files;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasFiles() {
        return !files.isEmpty();
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", reasoning='" + (reasoning != null ? reasoning.length() + " chars" : "null") + '\'' +
                ", timestamp=" + timestamp +
                ", files=" + files.size() +
                ", toolCallId='" + toolCallId + '\'' +
                ", toolCalls=" + toolCalls.size() +
                '}';
    }

    /**
     * 文件附件
     */
    public record FileAttachment(
            /** 文件名 */
            String filename,
            /** 下载路径 */
            String url,
            /** 文件大小（字节） */
            long size,
            /** MIME 类型 */
            String mimeType
    ) {}
}
