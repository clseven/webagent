package com.example.sandbox.web.model.response;

import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;

import java.time.LocalDateTime;

/**
 * 文档响应
 *
 * @author example
 * @date 2026/05/31
 */
public class DocumentResponse {

    private Long id;
    private Long userId;
    private Long kbId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private int chunkCount;
    private String status;
    private String errorMsg;
    private String splitMode;
    private Integer chunkSize;
    private Integer overlap;
    private Integer totalTokens;
    private Boolean sandboxSynced;
    private LocalDateTime createdAt;

    public static DocumentResponse from(KnowledgeDocumentEntity entity) {
        DocumentResponse response = new DocumentResponse();
        response.setId(entity.getId());
        response.setUserId(entity.getUserId());
        response.setKbId(entity.getKbId());
        response.setFileName(entity.getFileName());
        response.setFileType(entity.getFileType());
        response.setFileSize(entity.getFileSize());
        response.setChunkCount(entity.getChunkCount());
        response.setStatus(entity.getStatus());
        response.setErrorMsg(entity.getErrorMsg());
        response.setSplitMode(entity.getSplitMode());
        response.setChunkSize(entity.getChunkSize());
        response.setOverlap(entity.getOverlap());
        response.setTotalTokens(entity.getTotalTokens());
        response.setSandboxSynced(entity.getSandboxSynced());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getSplitMode() { return splitMode; }
    public void setSplitMode(String splitMode) { this.splitMode = splitMode; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getOverlap() { return overlap; }
    public void setOverlap(Integer overlap) { this.overlap = overlap; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Boolean getSandboxSynced() { return sandboxSynced; }
    public void setSandboxSynced(Boolean sandboxSynced) { this.sandboxSynced = sandboxSynced; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
