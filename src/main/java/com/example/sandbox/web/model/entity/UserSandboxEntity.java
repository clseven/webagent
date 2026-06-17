package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户沙箱 Entity
 *
 * <p>一个用户对应一个沙箱，存储沙箱 ID 和 AIO 服务地址。</p>
 *
 * @author example
 * @date 2026/05/31
 */
@Entity
@Table(name = "user_sandbox")
@Getter
@Setter
public class UserSandboxEntity {

    /**
     * 用户 ID，同时作为用户沙箱表主键。
     */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * 用户当前绑定的沙箱 ID。
     */
    @Column(name = "sandbox_id", length = 128)
    private String sandboxId;

    /**
     * AIO 服务访问地址。
     */
    @Column(name = "aio_endpoint", length = 64)
    private String aioEndpoint;

    /**
     * 软删除标记。
     */
    @Column(name = "deleted")
    private Boolean deleted = false;

    /**
     * 绑定记录创建时间。
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 绑定记录最后更新时间。
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 创建用户沙箱实体并初始化时间字段。
     */
    public UserSandboxEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 使用用户 ID、沙箱 ID 和 AIO 地址创建用户沙箱绑定。
     *
     * @param userId      用户 ID
     * @param sandboxId   沙箱 ID
     * @param aioEndpoint AIO 服务访问地址
     */
    public UserSandboxEntity(Long userId, String sandboxId, String aioEndpoint) {
        this.userId = userId;
        this.sandboxId = sandboxId;
        this.aioEndpoint = aioEndpoint;
        this.deleted = false;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置沙箱 ID，并刷新更新时间。
     *
     * @param sandboxId 沙箱 ID
     */
    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置 AIO 服务访问地址，并刷新更新时间。
     *
     * @param aioEndpoint AIO 服务访问地址
     */
    public void setAioEndpoint(String aioEndpoint) {
        this.aioEndpoint = aioEndpoint;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置软删除标记，并刷新更新时间。
     *
     * @param deleted 是否已删除
     */
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
        this.updatedAt = LocalDateTime.now();
    }
}
