package com.example.sandbox.web.service.enhance;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;

/**
 * Query Rewrite 服务接口
 *
 * <p>把口语化、有指代丢失的 query，改写成 1~3 个独立、可搜索的 query。</p>
 *
 * @author example
 * @date 2026/06/05
 */
public interface QueryRewriteService {

    /**
     * 改写查询
     *
     * @param userMessage 用户当前消息
     * @param history 历史对话（用于消解指代）
     * @return 改写后的查询列表（1~3 个），失败时返回原始 userMessage
     */
    List<String> rewrite(String userMessage, List<ChatMessage> history);
}
