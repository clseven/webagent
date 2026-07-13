package com.example.sandbox.web.model.llm;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;

/**
 * Agent 暂停运行的协议级检查点。
 *
 * <p>检查点按版本保存完整消息顺序，使下一轮可以从最后一个工具结果之后继续，
 * 而不是依赖面向前端的展示事件反推模型协议。</p>
 *
 * @param version  检查点结构版本
 * @param messages 按原序保存的模型消息
 */
public record AgentRunCheckpoint(int version, List<ChatMessageCheckpoint> messages) {

    /** 当前检查点结构版本。 */
    public static final int CURRENT_VERSION = 1;

    /**
     * 从运行时消息创建检查点。
     *
     * @param messages 当前 ReAct 消息链
     * @return 不含图片和展示字段的持久化检查点
     */
    public static AgentRunCheckpoint fromMessages(List<ChatMessage> messages) {
        List<ChatMessageCheckpoint> snapshots = messages == null
                ? List.of()
                : messages.stream().map(ChatMessageCheckpoint::fromMessage).toList();
        return new AgentRunCheckpoint(CURRENT_VERSION, snapshots);
    }

    /**
     * 创建空检查点，用于损坏或缺失数据的安全降级。
     *
     * @return 当前版本的空检查点
     */
    public static AgentRunCheckpoint empty() {
        return new AgentRunCheckpoint(CURRENT_VERSION, List.of());
    }

    /**
     * 恢复运行时消息链。
     *
     * @return 保持原始顺序和工具调用关联的消息列表
     */
    public List<ChatMessage> toMessages() {
        if (messages == null || version != CURRENT_VERSION) {
            return List.of();
        }
        return messages.stream().map(ChatMessageCheckpoint::toMessage).toList();
    }
}
