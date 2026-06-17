package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户账号实体。
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_token", columnList = "token"),
    @Index(name = "idx_users_username", columnList = "username", unique = true)
})
@Getter
@Setter
public class UserEntity {

    /**
     * 用户主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户名。
     */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /**
     * BCrypt 后的密码哈希。
     */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    /**
     * 用户访问令牌。
     */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /**
     * 用户创建时间。
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 创建用户实体并初始化创建时间。
     */
    public UserEntity() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 重新生成用户访问令牌。
     */
    public void regenerateToken() {
        this.token = java.util.UUID.randomUUID().toString();
    }
}
