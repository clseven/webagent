package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.converter.EntityConverter;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ChatMessageEntity;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.response.SkillView;
import com.example.sandbox.web.repository.ChatMessageRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.ConversationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对话记忆服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    /**
     * 数据库存储标题的最大字符数，和实体列长度保持一致。
     */
    private static final int SESSION_TITLE_MAX_CODE_POINTS = 120;

    private final ConversationSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AgentSkillRuntimeService skillRuntimeService;

    public ConversationServiceImpl(ConversationSessionRepository sessionRepository,
                                  ChatMessageRepository messageRepository,
                                  AgentSkillRuntimeService skillRuntimeService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.skillRuntimeService = skillRuntimeService;
    }

    @Override
    @Transactional
    public void addUserMessage(String sessionId, String content) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        ChatMessageEntity message = EntityConverter.toChatMessageEntity(session, "user", content);
        messageRepository.save(message);
    }

    /**
     * 更新由模型自动生成的会话标题。
     *
     * <p>只覆盖空标题或默认标题，避免异步标题任务晚到时覆盖用户已确认的标题。
     * 标题为空或清洗后为空时直接跳过，不影响会话保留。</p>
     *
     * @param sessionId 会话 ID
     * @param title     模型生成的标题
     */
    @Override
    @Transactional
    public void updateGeneratedTitle(String sessionId, String title) {
        String normalizedTitle = normalizeGeneratedTitle(title);
        if (normalizedTitle.isEmpty()) {
            return;
        }

        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        String currentTitle = session.getTitle();
        if (currentTitle != null && !currentTitle.isBlank()
                && !ConversationSession.DEFAULT_TITLE.equals(currentTitle)) {
            return;
        }

        session.setTitle(normalizedTitle);
        sessionRepository.save(session);
    }

    @Override
    @Transactional
    public void addAssistantMessage(String sessionId, String content) {
        addAssistantMessage(sessionId, content, null);
    }

    @Override
    @Transactional
    public void addAssistantMessage(String sessionId, String content, String reasoning) {
        addAssistantMessage(sessionId, content, reasoning, List.of());
    }

    /**
     * 保存助手消息及其过程展示事件。
     *
     * @param sessionId 会话 ID
     * @param content   助手回复正文
     * @param reasoning 思考链内容，可为 null
     * @param events    前端历史恢复所需的过程事件，可为空
     */
    @Override
    @Transactional
    public void addAssistantMessage(String sessionId, String content, String reasoning, List<Map<String, Object>> events) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        ChatMessageEntity message = EntityConverter.toChatMessageEntity(
                session, "assistant", content, reasoning, events);
        messageRepository.save(message);
    }

    @Override
    public List<ChatMessage> getHistory(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        List<ChatMessageEntity> messages = messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        return EntityConverter.toChatMessageList(messages);
    }

    @Override
    @Transactional
    public void clearHistory(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        messageRepository.deleteBySessionId(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public String buildSystemPrompt(String sessionId) {
        return skillRuntimeService.buildEnabledSkillPrompt(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getRecentHistory(String sessionId, int limit) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        List<ChatMessageEntity> allMessages = messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        if (allMessages.size() <= limit) {
            return EntityConverter.toChatMessageList(allMessages);
        }
        var recent = allMessages.subList(allMessages.size() - limit, allMessages.size());
        return EntityConverter.toChatMessageList(recent);
    }

    @Override
    @Transactional
    public void enableSkill(String sessionId, String skillId) {
        skillRuntimeService.enableSkill(sessionId, skillId);
    }

    @Override
    @Transactional
    public void disableSkill(String sessionId, String skillId) {
        skillRuntimeService.disableSkill(sessionId, skillId);
    }

    @Override
    public Set<String> getEnabledSkillIds(String sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        return session.getEnabledSkillIds();
    }

    // ========== 供 AgentService 调用的方法 ==========

    /**
     * 创建新会话（数据库）
     */
    @Transactional
    public ConversationSession createSession() {
        Long userId = UserContext.getCurrentUserId();
        ConversationSessionEntity entity = new ConversationSessionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setTitle(ConversationSession.DEFAULT_TITLE);
        entity = sessionRepository.save(entity);
        return EntityConverter.toConversationSession(entity);
    }

    /**
     * 创建新会话（关联 Agent 应用）
     */
    @Transactional
    public ConversationSession createSession(Long appId) {
        Long userId = UserContext.getCurrentUserId();
        ConversationSessionEntity entity = new ConversationSessionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setAppId(appId);
        entity.setTitle(ConversationSession.DEFAULT_TITLE);
        entity = sessionRepository.save(entity);
        return EntityConverter.toConversationSession(entity);
    }

    /**
     * 删除会话实体，级联删除消息并清理会话技能关联。
     *
     * @param sessionId 会话 ID
     */
    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        if (sessionRepository.existsById(sessionId)) {
            sessionRepository.deleteById(sessionId);
        }
    }

    /**
     * 在同一事务中删除指定用户拥有的多个会话实体。
     *
     * <p>使用实体删除以保留消息和会话技能关联的级联清理；不存在或属于其他用户的 ID 会被忽略。</p>
     *
     * @param sessionIds 待删除的会话 ID 集合
     * @param userId     当前用户 ID
     * @return 实际成功删除的会话 ID
     */
    @Override
    @Transactional
    public List<String> deleteSessionsOwnedByUser(Set<String> sessionIds, Long userId) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<ConversationSessionEntity> sessions = sessionRepository.findByIdInAndUserId(sessionIds, userId);
        List<String> deletedSessionIds = sessions.stream()
                .map(ConversationSessionEntity::getId)
                .toList();
        sessionRepository.deleteAll(sessions);
        return deletedSessionIds;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Skill> getEnabledSkills(String sessionId) {
        return skillRuntimeService.getEnabledSkills(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public String getSkillContent(String sessionId, String skillId) {
        return skillRuntimeService.getSkillContent(sessionId, skillId);
    }

    @Override
    @Transactional(readOnly = true)
    public String getSkillReference(String sessionId, String skillId, String path) {
        return skillRuntimeService.getSkillReference(sessionId, skillId, path);
    }

    /**
     * 规整自动生成标题，避免空白、换行或超长内容进入会话列表。
     *
     * @param title 原始标题
     * @return 可持久化标题；不可用时返回空字符串
     */
    private String normalizeGeneratedTitle(String title) {
        if (title == null) {
            return "";
        }
        String normalized = title.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount <= SESSION_TITLE_MAX_CODE_POINTS) {
            return normalized;
        }
        int endIndex = normalized.offsetByCodePoints(0, SESSION_TITLE_MAX_CODE_POINTS);
        return normalized.substring(0, endIndex);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkillView> listSessionSkills(String sessionId) {
        return skillRuntimeService.listSessionSkills(sessionId);
    }

    /**
     * 获取会话（数据库）
     */
    @Transactional(readOnly = true)
    public ConversationSession getSession(String sessionId) {
        ConversationSessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        return EntityConverter.toConversationSession(entity);
    }

    /**
     * 获取用户的所有会话
     */
    @Transactional(readOnly = true)
    public List<ConversationSession> listUserSessions(Long userId) {
        List<ConversationSessionEntity> entities = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return entities.stream()
                .map(EntityConverter::toConversationSession)
                .toList();
    }
}
