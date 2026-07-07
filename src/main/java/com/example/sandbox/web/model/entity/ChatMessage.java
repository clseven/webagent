package com.example.sandbox.web.model.entity;

import com.example.sandbox.web.model.llm.LlmToolCall;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 对话消息
 *
 * @author example
 * @date 2026/05/14
 */
@Getter
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

    /**
     * assistant 消息的过程展示事件。
     *
     * <p>事件结构与前端 msg.events 保持一致，用于刷新页面后恢复 plan、thinking 和工具结果展示。</p>
     */
    private final List<Map<String, Object>> events;

    /**
     * 多模态内容块（仅内存使用，不持久化）。
     *
     * <p>非 null 时对应 OpenAI vision 格式的 content 数组，用于携带图片数据。
     * 格式与 {@link com.example.sandbox.web.model.llm.LlmMessage#getContentParts()} 一致。
     * 从数据库恢复的消息此字段始终为 null（图片内容是一次性的，不需要重放）。</p>
     */
    private final List<Map<String, Object>> contentParts;

    private ChatMessage(String role, String content, String reasoning, Long timestamp,
                        List<FileAttachment> files, String toolCallId, List<LlmToolCall> toolCalls,
                        List<Map<String, Object>> events, List<Map<String, Object>> contentParts) {
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.timestamp = timestamp;
        this.files = files != null ? List.copyOf(files) : List.of();
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.events = events != null ? List.copyOf(events) : List.of();
        this.contentParts = contentParts != null ? List.copyOf(contentParts) : null;
    }

    /**
     * 从持久化数据恢复聊天消息。
     *
     * <p>当前数据库消息不保存附件和工具调用协议字段，因此恢复时使用空值。</p>
     */
    public static ChatMessage restore(String role, String content, String reasoning, Long timestamp) {
        return restore(role, content, reasoning, timestamp, null);
    }

    /**
     * 从持久化数据恢复聊天消息，并携带 assistant 过程事件。
     *
     * @param role      消息角色
     * @param content   消息正文
     * @param reasoning 思考链内容，可为 null
     * @param timestamp 消息时间戳
     * @param events    前端过程展示事件，可为空
     * @return 恢复后的聊天消息
     */
    public static ChatMessage restore(String role, String content, String reasoning, Long timestamp,
                                      List<Map<String, Object>> events) {
        return new ChatMessage(role, content, reasoning, timestamp, null, null, null, events, null);
    }

    /**
     * 创建用户消息
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content, null, Instant.now().toEpochMilli(), null, null, null, null, null);
    }

    /**
     * 创建携带图片的用户消息（多模态，仅内存使用）。
     *
     * @param text       文字说明，可为空字符串
     * @param imageBytes 图片原始字节
     * @param mimeType   图片 MIME 类型，如 "image/png"
     * @return 多模态用户消息
     */
    public static ChatMessage userMessageWithImage(String text, byte[] imageBytes, String mimeType) {
        String b64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        List<Map<String, Object>> parts = new java.util.ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(Map.of("type", "text", "text", text));
        }
        parts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mimeType + ";base64," + b64)
        ));
        return new ChatMessage("user", text, null, Instant.now().toEpochMilli(),
                null, null, null, null, parts);
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage("assistant", content, null, Instant.now().toEpochMilli(), null, null, null, null, null);
    }

    /**
     * 创建助手消息（带思考链）
     */
    public static ChatMessage assistantMessage(String content, String reasoning) {
        return new ChatMessage("assistant", content, reasoning, Instant.now().toEpochMilli(), null, null, null, null, null);
    }

    /**
     * 创建助手消息（带文件附件）
     */
    public static ChatMessage assistantMessage(String content, List<FileAttachment> files) {
        return new ChatMessage("assistant", content, null, Instant.now().toEpochMilli(), files, null, null, null, null);
    }

    /**
     * 创建助手消息（带思考链和文件附件）
     */
    public static ChatMessage assistantMessage(String content, String reasoning, List<FileAttachment> files) {
        return new ChatMessage("assistant", content, reasoning, Instant.now().toEpochMilli(), files, null, null, null, null);
    }

    /**
     * 创建助手工具调用消息（原生 tool calling 协议）
     */
    public static ChatMessage assistantToolCallMessage(LlmToolCall toolCall) {
        return new ChatMessage("assistant", null, null, Instant.now().toEpochMilli(),
                null, null, List.of(toolCall), null, null);
    }

    /**
     * 创建携带多个工具调用的助手消息（并发 tool calling 协议）。
     *
     * <p>OpenAI 规范要求：一轮并发的多个 tool_call 放在同一条 assistant 消息的 tool_calls 数组里，
     * 随后每个 tool_call 各跟一条 tool 结果消息。</p>
     *
     * @param toolCalls 本轮全部工具调用
     * @return assistant 工具调用消息
     */
    public static ChatMessage assistantToolCallsMessage(List<LlmToolCall> toolCalls) {
        return new ChatMessage("assistant", null, null, Instant.now().toEpochMilli(),
                null, null, List.copyOf(toolCalls), null, null);
    }

    /**
     * 创建系统消息
     */
    public static ChatMessage systemMessage(String content) {
        return new ChatMessage("system", content, null, Instant.now().toEpochMilli(), null, null, null, null, null);
    }

    /**
     * 创建工具结果消息（原生 tool calling 协议）
     *
     * @param toolCallId 工具调用 ID，关联 assistant 消息中的 tool_calls[].id
     * @param content    工具执行结果
     */
    public static ChatMessage toolMessage(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, Instant.now().toEpochMilli(), null, toolCallId, null, null, null);
    }

    /**
     * 判断当前消息是否带有文件附件。
     *
     * @return true 表示存在文件附件
     */
    public boolean hasFiles() {
        return !files.isEmpty();
    }

    /**
     * 判断当前消息是否带有可恢复展示的过程事件。
     *
     * @return true 表示存在 plan、thinking 或工具结果等过程事件
     */
    public boolean hasEvents() {
        return !events.isEmpty();
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
                ", events=" + events.size() +
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
