package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识切片实体
 *
 * @author example
 * @date 2026/05/31
 */
@Entity
@Table(name = "knowledge_chunk", indexes = {
        @Index(name = "idx_kc_document", columnList = "document_id"),
        @Index(name = "idx_kc_user", columnList = "user_id")
})
@Getter
@Setter
public class KnowledgeChunkEntity {

    /**
     * 切片主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 切片所属文档 ID。
     */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /**
     * 切片所属用户 ID。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 切片所属知识库 ID。
     */
    @Column(name = "kb_id")
    private Long kbId;

    /**
     * 切片在文档中的序号。
     */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /**
     * 切片文本内容。
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 切片估算 token 数。
     */
    @Column(name = "token_count")
    private int tokenCount;

    /**
     * 切片在原文中的字符起始偏移量（用于预览时定位原文位置）
     */
    @Column(name = "start_offset")
    private Integer startOffset;

    /**
     * 切片在原文中的字符结束偏移量（用于预览时定位原文位置）
     */
    @Column(name = "end_offset")
    private Integer endOffset;

    /**
     * 切片创建时间。
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 创建知识切片实体并初始化创建时间。
     */
    public KnowledgeChunkEntity() {
        this.createdAt = LocalDateTime.now();
    }
}
