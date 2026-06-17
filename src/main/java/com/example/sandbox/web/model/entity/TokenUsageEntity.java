package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Token 用量记录实体
 */
@Entity
@Table(name = "token_usage", indexes = {
        @Index(name = "idx_token_user_id", columnList = "userId"),
        @Index(name = "idx_token_session_id", columnList = "sessionId"),
        @Index(name = "idx_token_created_at", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class TokenUsageEntity {

    /**
     * 用量记录主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 产生用量的用户 ID。
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * 产生用量的会话 ID。
     */
    @Column(length = 36)
    private String sessionId;

    /**
     * 输入 token 数。
     */
    @Column(nullable = false)
    private int promptTokens;

    /**
     * 输出 token 数。
     */
    @Column(nullable = false)
    private int completionTokens;

    /**
     * 命中缓存的 token 数。
     */
    @Column(nullable = false)
    private int cacheHitTokens;

    /**
     * 总 token 数。
     */
    @Column(nullable = false)
    private int totalTokens;

    /**
     * 产生用量的模型名称。
     */
    @Column(length = 50)
    private String model;

    /**
     * 消息或阶段类型。
     */
    @Column(length = 20)
    private String messageType;

    /**
     * 用量记录创建时间。
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 持久化前补齐创建时间。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * 创建完整的 token 用量记录。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param cacheHitTokens   命中缓存 token 数
     * @param totalTokens      总 token 数
     * @param model            模型名称
     * @param messageType      消息或阶段类型
     */
    public TokenUsageEntity(Long userId, String sessionId, int promptTokens, int completionTokens,
                            int cacheHitTokens, int totalTokens, String model, String messageType) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cacheHitTokens = cacheHitTokens;
        this.totalTokens = totalTokens;
        this.model = model;
        this.messageType = messageType;
    }
}
