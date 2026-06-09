package com.example.sandbox.web.service.enhance;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;

/**
 * 知识库检索增强服务接口
 *
 * <p>封装 Query Rewrite + 向量检索 + Rerank 完整流程，
 * 返回可注入到 system prompt 的上下文文本。</p>
 *
 * @author example
 * @date 2026/06/05
 */
public interface KnowledgeEnhancer {

    /**
     * 执行检索增强
     *
     * @param userId 用户 ID
     * @param kbIds 知识库 ID 列表（检索范围）
     * @param userMessage 用户当前消息
     * @param history 历史对话（用于 Query Rewrite）
     * @return 增强后的上下文文本，可注入到 system prompt；无结果时返回空字符串
     */
    String enhance(Long userId, List<Long> kbIds, String userMessage, List<ChatMessage> history);
}
