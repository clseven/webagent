package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
@Getter
@Setter
public class ConversationSessionEntity {

    /**
     * 会话 ID。
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 当前会话绑定的沙箱 ID。
     */
    @Column(name = "sandbox_id", length = 128)
    private String sandboxId;

    /**
     * 会话所属用户 ID。
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 会话关联的 Agent 应用 ID。
     */
    @Column(name = "app_id")
    private Long appId;

    /**
     * 会话创建时间。
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 会话最后更新时间。
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 会话下的消息集合。
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<ChatMessageEntity> messages = new HashSet<>();

    /**
     * 当前会话启用的 Skill ID 集合。
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "session_skill",
        joinColumns = @JoinColumn(name = "session_id")
    )
    @Column(name = "skill_id", length = 64)
    private Set<String> enabledSkillIds = new HashSet<>();

    /**
     * 创建会话实体并初始化创建、更新时间。
     */
    public ConversationSessionEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置沙箱 ID，并刷新会话更新时间。
     *
     * @param sandboxId 当前会话绑定的沙箱 ID
     */
    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 添加消息并维护双向关联，同时刷新会话更新时间。
     *
     * @param message 待加入当前会话的消息实体
     */
    public void addMessage(ChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 启用指定 Skill，并刷新会话更新时间。
     *
     * @param skillId 需要启用的 Skill ID
     */
    public void enableSkill(String skillId) {
        enabledSkillIds.add(skillId);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 禁用指定 Skill，并刷新会话更新时间。
     *
     * @param skillId 需要禁用的 Skill ID
     */
    public void disableSkill(String skillId) {
        enabledSkillIds.remove(skillId);
        this.updatedAt = LocalDateTime.now();
    }
}
