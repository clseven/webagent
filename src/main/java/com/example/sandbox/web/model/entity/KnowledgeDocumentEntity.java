package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库文档实体
 *
 * @author example
 * @date 2026/05/31
 */
@Entity
@Table(name = "knowledge_document", indexes = {
        @Index(name = "idx_kd_user", columnList = "user_id"),
        @Index(name = "idx_kd_status", columnList = "status"),
        @Index(name = "idx_kd_kb", columnList = "kb_id")
})
public class KnowledgeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_type", length = 20)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(name = "chunk_count")
    private int chunkCount;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "error_msg")
    private String errorMsg;

    @Column(name = "split_mode", length = 20)
    private String splitMode;

    @Column(name = "chunk_size")
    private Integer chunkSize;

    @Column(name = "overlap")
    private Integer overlap;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /**
     * 沙箱同步状态：true=已成功同步到用户沙箱(/home/gem/knowledge/)，false=同步失败或未同步
     * 用于文件预览功能：false 时前端提示"沙箱同步失败，无法预览"
     */
    @Column(name = "sandbox_synced")
    private Boolean sandboxSynced;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public KnowledgeDocumentEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "PENDING";
        this.chunkCount = 0;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getSplitMode() {
        return splitMode;
    }

    public void setSplitMode(String splitMode) {
        this.splitMode = splitMode;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getOverlap() {
        return overlap;
    }

    public void setOverlap(Integer overlap) {
        this.overlap = overlap;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Boolean getSandboxSynced() {
        return sandboxSynced;
    }

    public void setSandboxSynced(Boolean sandboxSynced) {
        this.sandboxSynced = sandboxSynced;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
