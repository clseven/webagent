package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;

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
public class KnowledgeChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id")
    private Long kbId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public KnowledgeChunkEntity() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(Integer startOffset) {
        this.startOffset = startOffset;
    }

    public Integer getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(Integer endOffset) {
        this.endOffset = endOffset;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
