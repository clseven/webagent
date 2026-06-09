package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
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
public class UserSandboxEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "sandbox_id", length = 128)
    private String sandboxId;

    @Column(name = "aio_endpoint", length = 64)
    private String aioEndpoint;

    @Column(name = "deleted")
    private Boolean deleted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserSandboxEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public UserSandboxEntity(Long userId, String sandboxId, String aioEndpoint) {
        this.userId = userId;
        this.sandboxId = sandboxId;
        this.aioEndpoint = aioEndpoint;
        this.deleted = false;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
        this.updatedAt = LocalDateTime.now();
    }

    public String getAioEndpoint() {
        return aioEndpoint;
    }

    public void setAioEndpoint(String aioEndpoint) {
        this.aioEndpoint = aioEndpoint;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
        this.updatedAt = LocalDateTime.now();
    }
}
