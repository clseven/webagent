package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 会话 Entity
 *
 * @author example
 * @date 2026/05/14
 */
@Entity
@Table(name = "conversation_session")
public class ConversationSessionEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "sandbox_id", length = 128)
    private String sandboxId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "app_id")
    private Long appId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<ChatMessageEntity> messages = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "session_skill",
        joinColumns = @JoinColumn(name = "session_id")
    )
    @Column(name = "skill_id", length = 64)
    private Set<String> enabledSkillIds = new HashSet<>();

    public ConversationSessionEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Set<ChatMessageEntity> getMessages() {
        return messages;
    }

    public void setMessages(Set<ChatMessageEntity> messages) {
        this.messages = messages;
    }

    public Set<String> getEnabledSkillIds() {
        return enabledSkillIds;
    }

    public void setEnabledSkillIds(Set<String> enabledSkillIds) {
        this.enabledSkillIds = enabledSkillIds;
    }

    public void addMessage(ChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
        this.updatedAt = LocalDateTime.now();
    }

    public void enableSkill(String skillId) {
        enabledSkillIds.add(skillId);
        this.updatedAt = LocalDateTime.now();
    }

    public void disableSkill(String skillId) {
        enabledSkillIds.remove(skillId);
        this.updatedAt = LocalDateTime.now();
    }
}