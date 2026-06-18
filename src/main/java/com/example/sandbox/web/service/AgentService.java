package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.sse.SseEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 编排服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface AgentService {

    /**
     * 创建新会话
     *
     * @return 新创建的会话
     */
    ConversationSession createSession();

    /**
     * 创建新会话（关联 Agent 应用）
     *
     * @param appId Agent 应用 ID
     * @return 新创建的会话
     */
    ConversationSession createSession(Long appId);

    /**
     * 删除当前用户拥有的会话。
     *
     * @param sessionId 会话 ID
     */
    void deleteSession(String sessionId);

    /**
     * 获取会话
     *
     * @param sessionId 会话 ID
     * @return 会话信息
     */
    ConversationSession getSession(String sessionId);

    /**
     * 对话（同步版本）
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 助手响应
     */
    ChatMessage chat(String sessionId, String userMessage);

    /**
     * 流式对话（SSE 版本）
     *
     * <p>实时返回思考过程、工具调用、最终答案等事件。</p>
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return SSE 事件流
     */
    Flux<SseEvent> chatStream(String sessionId, String userMessage);
}
