package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent 应用实体
 *
 * @author example
 * @date 2026/06/02
 */
@Entity
@Table(name = "agent_app", indexes = {
        @Index(name = "idx_app_user", columnList = "user_id")
})
public class AgentAppEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 关联的知识库 ID 集合
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_app_knowledge", joinColumns = @JoinColumn(name = "app_id"))
    @Column(name = "kb_id")
    private Set<Long> knowledgeBaseIds = new HashSet<>();

    /**
     * 关联的 Skill ID 集合
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_app_skill", joinColumns = @JoinColumn(name = "app_id"))
    @Column(name = "skill_id", length = 64)
    private Set<String> skillIds = new HashSet<>();

    public AgentAppEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Set<Long> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(Set<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }

    public Set<String> getSkillIds() { return skillIds; }
    public void setSkillIds(Set<String> skillIds) { this.skillIds = skillIds; }
}
