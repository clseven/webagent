package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库实体
 *
 * @author example
 * @date 2026/06/02
 */
@Entity
@Table(name = "knowledge_base", indexes = {
        @Index(name = "idx_kb_user", columnList = "user_id")
})
@Getter
@Setter
public class KnowledgeBaseEntity {

    /**
     * 知识库主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 知识库所属用户 ID。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 知识库名称。
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 知识库描述。
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 知识库创建时间。
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 知识库最后更新时间。
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 创建知识库实体并初始化创建、更新时间。
     */
    public KnowledgeBaseEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
