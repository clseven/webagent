package com.example.sandbox.web.model.converter;

import com.example.sandbox.web.model.entity.ChatMessageEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Entity 与 Domain Model 转换工具
 *
 * @author example
 * @date 2026/05/14
 */
public class EntityConverter {

    /**
     * 消息过程事件的 JSON 序列化器。
     *
     * <p>该转换器只保存前端展示快照，不参与 LLM 上下文构建，因此使用通用 Map 结构保持兼容性。</p>
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * assistant 过程事件列表的反序列化类型。
     */
    private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE = new TypeReference<>() {
    };

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
                entity.getTimestamp(),
                parseEvents(entity.getEventsJson()));
    }

    public static ChatMessageEntity toChatMessageEntity(ConversationSessionEntity session, String role, String content) {
        return toChatMessageEntity(session, role, content, null, List.of());
    }

    /**
     * 创建可持久化的聊天消息实体。
     *
     * @param session   所属会话实体
     * @param role      消息角色
     * @param content   消息正文
     * @param reasoning assistant 思考链内容，可为 null
     * @param events    assistant 过程展示事件，可为空
     * @return 已填充基础字段的消息实体
     */
    public static ChatMessageEntity toChatMessageEntity(ConversationSessionEntity session, String role, String content,
                                                        String reasoning, List<Map<String, Object>> events) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSession(session);
        entity.setRole(role);
        entity.setContent(content);
        entity.setReasoning(reasoning);
        entity.setEventsJson(serializeEvents(events));
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

            var titleField = ConversationSession.class.getDeclaredField("title");
            titleField.setAccessible(true);
            titleField.set(session, entity.getTitle() == null || entity.getTitle().isBlank()
                    ? ConversationSession.DEFAULT_TITLE
                    : entity.getTitle());

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

    /**
     * 将过程事件序列化为数据库 JSON 字符串。
     *
     * @param events 与前端 msg.events 兼容的事件列表
     * @return JSON 字符串；空事件返回 null，避免无意义占用存储
     * @throws IllegalArgumentException 当事件结构无法序列化时抛出，调用方应修正事件构造逻辑
     */
    private static String serializeEvents(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize chat message events", e);
        }
    }

    /**
     * 从数据库 JSON 恢复过程事件。
     *
     * <p>历史加载不应因为单条展示快照损坏而失败，因此解析异常时返回空列表。</p>
     *
     * @param eventsJson 数据库中的事件 JSON
     * @return 可供前端展示的事件列表；没有或解析失败时为空列表
     */
    private static List<Map<String, Object>> parseEvents(String eventsJson) {
        if (eventsJson == null || eventsJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(eventsJson, EVENT_LIST_TYPE);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

}
