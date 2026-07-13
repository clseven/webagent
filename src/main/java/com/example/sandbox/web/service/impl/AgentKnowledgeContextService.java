package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.service.AgentAppService;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.enhance.KnowledgeEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 知识库上下文服务。
 *
 * <p>负责加载 Agent 应用配置、构造知识库工具描述以及生成规划/执行可共用的知识库增强上下文。</p>
 */
@Service
public class AgentKnowledgeContextService {

    private static final Logger log = LoggerFactory.getLogger(AgentKnowledgeContextService.class);

    /** Agent 应用服务，用于加载应用配置。 */
    private final AgentAppService agentAppService;

    /** 知识库服务，用于读取知识库描述。 */
    private final KnowledgeService knowledgeService;

    /** 知识库增强服务，用于 Query Rewrite 和 Rerank。 */
    private final KnowledgeEnhancer knowledgeEnhancer;

    /**
     * 创建知识库上下文服务。
     *
     * @param agentAppService  Agent 应用服务
     * @param knowledgeService 知识库服务
     * @param knowledgeEnhancer 知识库增强服务
     */
    public AgentKnowledgeContextService(AgentAppService agentAppService,
                                        KnowledgeService knowledgeService,
                                        KnowledgeEnhancer knowledgeEnhancer) {
        this.agentAppService = agentAppService;
        this.knowledgeService = knowledgeService;
        this.knowledgeEnhancer = knowledgeEnhancer;
    }

    /**
     * 加载当前会话关联的 Agent 应用。
     *
     * <p>应用配置加载失败不阻断对话主流程，调用方会按未关联应用继续执行。</p>
     *
     * @param session 当前会话
     * @return 应用实体；未关联或加载失败时返回 null
     */
    public AgentAppEntity loadApp(ConversationSession session) {
        Long appId = session.getAppId();
        if (appId == null) {
            return null;
        }
        try {
            return agentAppService.getApp(appId);
        } catch (Exception e) {
            log.warn("加载 Agent 应用配置失败: appId={}", appId, e);
            return null;
        }
    }

    /**
     * 构建知识库检索工具的动态描述。
     *
     * @param app 当前应用；可为 null
     * @return 知识库描述；无关联知识库时返回 null
     */
    public String buildKnowledgeDescription(AgentAppEntity app) {
        if (app == null || app.getKnowledgeBaseIds().isEmpty()) {
            return null;
        }

        StringBuilder kbDescBuilder = new StringBuilder();
        kbDescBuilder.append("从知识库中检索相关信息。");
        for (Long kbId : app.getKnowledgeBaseIds()) {
            try {
                String desc = knowledgeService.getKnowledgeBaseDescription(kbId);
                if (desc != null && !desc.isEmpty()) {
                    kbDescBuilder.append(desc).append(" ");
                }
            } catch (Exception e) {
                log.warn("获取知识库描述失败: kbId={}", kbId, e);
            }
        }

        String description = kbDescBuilder.toString();
        log.info("【知识库注入】应用: {}, 知识库: {}, 描述: {}",
                app.getId(), app.getKnowledgeBaseIds(),
                description.length() > 100 ? description.substring(0, 100) + "..." : description);
        return description;
    }

    /**
     * 构建当前轮对话的知识库增强上下文。
     *
     * @param userId      当前用户 ID
     * @param app         当前应用；可为 null
     * @param userMessage 用户消息
     * @param history     本轮用户消息入库前的历史消息
     * @param logPrefix   日志前缀，用于区分同步和流式入口
     * @return 增强上下文；无关联知识库或无结果时返回空字符串
     */
    public String enhance(Long userId, AgentAppEntity app, String userMessage,
                          List<ChatMessage> history, String logPrefix) {
        if (app == null || app.getKnowledgeBaseIds().isEmpty()) {
            return "";
        }
        List<Long> kbIds = new ArrayList<>(app.getKnowledgeBaseIds());
        String enhancedContext = knowledgeEnhancer.enhance(userId, kbIds, userMessage, history);
        if (!enhancedContext.isEmpty()) {
            log.info("{}检索上下文已生成: {} 字符", logPrefix, enhancedContext.length());
        }
        return enhancedContext;
    }
}
