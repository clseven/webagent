package com.example.sandbox.web.model.llm;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;

/**
 * 新一轮 Agent 执行可使用的上轮续接资料。
 *
 * @param resumeHistory               精确检查点恢复出的模型消息；旧数据兼容路径为空
 * @param context                     注入规划器和执行器的续接说明
 * @param exactCheckpoint             是否来自协议级检查点
 * @param suppressLatestHistoryMessage 是否应移除最近一条旧超限提示
 */
public record AgentContinuation(
        List<ChatMessage> resumeHistory,
        String context,
        boolean exactCheckpoint,
        boolean suppressLatestHistoryMessage
) {

    /**
     * 创建无续接状态。
     *
     * @return 空续接资料
     */
    public static AgentContinuation none() {
        return new AgentContinuation(List.of(), "", false, false);
    }

    /**
     * 判断当前是否包含可用的续接资料。
     *
     * @return true 表示存在精确检查点或旧事件上下文
     */
    public boolean available() {
        return exactCheckpoint || (context != null && !context.isBlank());
    }
}
