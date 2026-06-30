package com.example.sandbox.web.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 沙箱工作区可见目录记忆实体。
 *
 * <h3>用途</h3>
 * <p>记录前端工作区面板可见的 {@code /home/gem} 非隐藏目录树，帮助 Agent 在不读取文件内容的前提下了解工作区结构。</p>
 *
 * <h3>注意</h3>
 * <p>本实体只保存路径、目录标记、大小和可见性状态，不保存文件正文、摘要或向量。</p>
 */
@Entity
@Table(name = "workspace_directory_memory", indexes = {
        @Index(name = "idx_wdm_user_session", columnList = "user_id,session_id"),
        @Index(name = "idx_wdm_path", columnList = "path"),
        @Index(name = "idx_wdm_deleted", columnList = "deleted")
})
@Getter
@Setter
public class WorkspaceDirectoryMemoryEntity {

    /** 工作区可见根目录，与前端 WorkspaceBrowser 的默认根路径保持一致。 */
    public static final String VISIBLE_ROOT = "/home/gem";

    /** 目录记忆主键 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目录记忆所属用户 ID。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 目录记忆所属会话 ID。 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 扫描时用户绑定的沙箱 ID，可为空表示未找到持久化沙箱记录。 */
    @Column(name = "sandbox_id", length = 128)
    private String sandboxId;

    /** 文件或目录的绝对路径。 */
    @Column(nullable = false, length = 1024)
    private String path;

    /** 文件或目录名称。 */
    @Column(nullable = false, length = 255)
    private String name;

    /** 父目录绝对路径。 */
    @Column(name = "parent_path", nullable = false, length = 1024)
    private String parentPath;

    /** 是否目录。 */
    @Column(name = "is_directory", nullable = false)
    private boolean directory;

    /** 相对 {@link #VISIBLE_ROOT} 的路径深度，顶层目录深度为 1。 */
    @Column(nullable = false)
    private int depth;

    /** 文件大小，目录为 null。 */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** 是否已从最近一次可见目录树中消失。 */
    @Column(nullable = false)
    private boolean deleted = false;

    /** 首次记录时间。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 最近一次扫描看到该路径的时间。 */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /**
     * 创建目录记忆实体并初始化时间字段。
     */
    public WorkspaceDirectoryMemoryEntity() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 用最新扫描结果刷新该路径的元数据。
     *
     * @param userId    所属用户 ID
     * @param sessionId 所属会话 ID
     * @param sandboxId 用户绑定的沙箱 ID，可为空
     * @param path      {@code /home/gem} 下的可见绝对路径
     * @param directory 是否目录
     * @param sizeBytes 文件大小；目录会强制保存为 null
     */
    public void refresh(Long userId, String sessionId, String sandboxId,
                        String path, boolean directory, Long sizeBytes) {
        if (path == null || !path.startsWith(VISIBLE_ROOT + "/")) {
            throw new IllegalArgumentException("目录记忆路径必须位于 " + VISIBLE_ROOT + " 下");
        }
        LocalDateTime now = LocalDateTime.now();
        this.userId = userId;
        this.sessionId = sessionId;
        this.sandboxId = sandboxId;
        this.path = path;
        this.name = extractName(path);
        this.parentPath = extractParentPath(path);
        this.directory = directory;
        this.depth = calculateDepth(path);
        this.sizeBytes = directory ? null : sizeBytes;
        this.deleted = false;
        this.lastSeenAt = now;
        this.updatedAt = now;
        if (this.createdAt == null) {
            this.createdAt = now;
        }
    }

    /**
     * 标记该路径已从当前可见目录树中消失。
     */
    public void markDeleted() {
        this.deleted = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 提取路径最后一段作为名称。
     *
     * @param path 绝对路径
     * @return 文件或目录名称
     */
    private String extractName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 提取父目录路径。
     *
     * @param path 绝对路径
     * @return 父目录绝对路径
     */
    private String extractParentPath(String path) {
        int index = path.lastIndexOf('/');
        if (index <= VISIBLE_ROOT.length()) {
            return VISIBLE_ROOT;
        }
        return path.substring(0, index);
    }

    /**
     * 计算相对可见根目录的路径深度。
     *
     * @param path 绝对路径
     * @return 顶层路径为 1，更深层逐级递增
     */
    private int calculateDepth(String path) {
        String relative = path.substring((VISIBLE_ROOT + "/").length());
        return relative.split("/").length;
    }
}
