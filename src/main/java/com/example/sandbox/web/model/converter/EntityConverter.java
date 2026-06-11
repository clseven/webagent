package com.example.sandbox.web.model.converter;

import com.example.sandbox.web.model.entity.ChatMessageEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Entity 与 Domain Model 转换工具
 *
 * @author example
 * @date 2026/05/14
 */
public class EntityConverter {

    private EntityConverter() {
    }

    // ========== ChatMessage ==========

    public static ChatMessage toChatMessage(ChatMessageEntity entity) {
        if (entity == null) {
            return null;
        }
        return ChatMessage.restore(
                entity.getRole(),
                entity.getContent(),
                entity.getReasoning(),
                entity.getTimestamp());
    }

    public static ChatMessageEntity toChatMessageEntity(ConversationSessionEntity session, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSession(session);
        entity.setRole(role);
        entity.setContent(content);
        entity.setTimestamp(System.currentTimeMillis());
        return entity;
    }

    public static List<ChatMessage> toChatMessageList(List<ChatMessageEntity> entities) {
        return entities.stream()
                .map(EntityConverter::toChatMessage)
                .collect(Collectors.toList());
    }

    // ========== ConversationSession ==========

    public static ConversationSession toConversationSession(ConversationSessionEntity entity) {
        if (entity == null) {
            return null;
        }
        ConversationSession session = new ConversationSession();
        // 反射复制字段（因为 ConversationSession 的 sessionId 是 final 的，需要特殊处理）
        copySessionFields(entity, session);
        return session;
    }

    private static void copySessionFields(ConversationSessionEntity entity, ConversationSession session) {
        try {
            // 使用反射设置 final 字段（仅用于转换，不影响业务）
            var sessionIdField = ConversationSession.class.getDeclaredField("sessionId");
            sessionIdField.setAccessible(true);
            sessionIdField.set(session, entity.getId());

            var sandboxIdField = ConversationSession.class.getDeclaredField("sandboxId");
            sandboxIdField.setAccessible(true);
            sandboxIdField.set(session, entity.getSandboxId());

            var createdAtField = ConversationSession.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(session, entity.getCreatedAt());

            var updatedAtField = ConversationSession.class.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(session, entity.getUpdatedAt());

            // 复制 userId
            var userIdField = ConversationSession.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            userIdField.set(session, entity.getUserId());

            // 复制 appId
            var appIdField = ConversationSession.class.getDeclaredField("appId");
            appIdField.setAccessible(true);
            appIdField.set(session, entity.getAppId());

            // 复制消息
            var messagesField = ConversationSession.class.getDeclaredField("messages");
            messagesField.setAccessible(true);
            List<ChatMessage> messages = entity.getMessages().stream()
                    .map(EntityConverter::toChatMessage)
                    .collect(Collectors.toList());
            messagesField.set(session, messages);

            // 复制启用的技能
            var enabledSkillsField = ConversationSession.class.getDeclaredField("enabledSkillIds");
            enabledSkillsField.setAccessible(true);
            enabledSkillsField.set(session, entity.getEnabledSkillIds());
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy session fields", e);
        }
    }

}
