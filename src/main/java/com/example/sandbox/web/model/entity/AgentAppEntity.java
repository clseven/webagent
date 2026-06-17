package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
@Getter
@Setter
public class AgentAppEntity {

    /**
     * 应用主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 应用所属用户 ID。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 应用名称。
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 应用描述。
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 应用创建时间。
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 应用最后更新时间。
     */
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

    /**
     * 创建应用实体并初始化创建、更新时间。
     */
    public AgentAppEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
