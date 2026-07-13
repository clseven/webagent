package com.example.sandbox.web.model.llm;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;

/**
 * 可持久化的模型协议消息快照。
 *
 * <p>只保存恢复 ReAct 消息链所需的文本和工具协议字段，不保存前端展示事件、
 * 文件附件或图片 base64，避免把一次性大对象写入检查点。</p>
 *
 * @param role       消息角色
 * @param content    消息正文
 * @param reasoning  模型 reasoning_content
 * @param timestamp  消息时间戳
 * @param toolCallId tool 结果关联的调用 ID
 * @param toolCalls  assistant 发起的同轮工具调用
 */
public record ChatMessageCheckpoint(
        String role,
        String content,
        String reasoning,
        Long timestamp,
        String toolCallId,
        List<LlmToolCall> toolCalls
) {

    /**
     * 从运行时消息创建持久化快照。
     *
     * @param message 运行时消息
     * @return 不包含图片和展示事件的协议快照
     */
    public static ChatMessageCheckpoint fromMessage(ChatMessage message) {
        return new ChatMessageCheckpoint(
                message.getRole(),
                message.getContent(),
                message.getReasoning(),
                message.getTimestamp(),
                message.getToolCallId(),
                message.getToolCalls());
    }

    /**
     * 将持久化快照恢复为运行时模型消息。
     *
     * @return 可重新交给 LLM 的协议消息
     */
    public ChatMessage toMessage() {
        return ChatMessage.restoreProtocol(
                role, content, reasoning, timestamp, toolCallId, toolCalls);
    }
}
