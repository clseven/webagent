package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

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
@Getter
@Setter
public class KnowledgeDocumentEntity {

    /**
     * 文档主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文档所属用户 ID。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 文档所属知识库 ID。
     */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /**
     * 原始文件名。
     */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
     * 文件类型或扩展名。
     */
    @Column(name = "file_type", length = 20)
    private String fileType;

    /**
     * 文件大小，单位字节。
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 文件在本地工作区中的存储路径。
     */
    @Column(name = "storage_path", length = 500)
    private String storagePath;

    /**
     * 文档切片数量。
     */
    @Column(name = "chunk_count")
    private int chunkCount;

    /**
     * 文档处理状态。
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * 文档处理失败时的错误信息。
     */
    @Column(name = "error_msg")
    private String errorMsg;

    /**
     * 文档切片模式。
     */
    @Column(name = "split_mode", length = 20)
    private String splitMode;

    /**
     * 切片大小。
     */
    @Column(name = "chunk_size")
    private Integer chunkSize;

    /**
     * 相邻切片重叠大小。
     */
    @Column(name = "overlap")
    private Integer overlap;

    /**
     * 文档估算总 token 数。
     */
    @Column(name = "total_tokens")
    private Integer totalTokens;

    /**
     * 沙箱同步状态：true=已成功同步到用户沙箱(/home/gem/knowledge/)，false=同步失败或未同步
     * 用于文件预览功能：false 时前端提示"沙箱同步失败，无法预览"
     */
    @Column(name = "sandbox_synced")
    private Boolean sandboxSynced;

    /**
     * 文档创建时间。
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 文档最后更新时间。
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 创建知识库文档实体，并初始化默认处理状态。
     */
    public KnowledgeDocumentEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "PENDING";
        this.chunkCount = 0;
    }

    /**
     * 设置文档处理状态，并刷新文档更新时间。
     *
     * @param status 新的文档处理状态
     */
    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
}
