package com.example.sandbox.web.model.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 对话会话
 *
 * @author example
 * @date 2026/05/14
 */
public class ConversationSession {

    /**
     * 会话唯一标识
     */
    private final String sessionId;

    /**
     * 消息历史
     */
    private final List<ChatMessage> messages;

    /**
     * 关联的沙盒实例 ID
     */
    private String sandboxId;

    /**
     * 启用的技能 ID 集合
     */
    private final Set<String> enabledSkillIds;

    /**
     * 创建时间
     */
    private final LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 所属用户 ID
     */
    private Long userId;

    /**
     * 关联的 Agent 应用 ID
     */
    private Long appId;

    public ConversationSession() {
        this.sessionId = UUID.randomUUID().toString();
        this.messages = new ArrayList<>();
        this.enabledSkillIds = new HashSet<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
        this.updatedAt = LocalDateTime.now();
    }

    public Set<String> getEnabledSkillIds() {
        return enabledSkillIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    /**
     * 添加消息并更新时间
     */
    public void addMessage(ChatMessage message) {
        messages.add(message);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 启用技能
     */
    public void enableSkill(String skillId) {
        enabledSkillIds.add(skillId);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 禁用技能
     */
    public void disableSkill(String skillId) {
        enabledSkillIds.remove(skillId);
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "ConversationSession{" +
                "sessionId='" + sessionId + '\'' +
                ", sandboxId='" + sandboxId + '\'' +
                ", messageCount=" + messages.size() +
                ", enabledSkillIds=" + enabledSkillIds +
                '}';
    }
}
