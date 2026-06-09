package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
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
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 36)
    private String sessionId;

    @Column(nullable = false)
    private int promptTokens;

    @Column(nullable = false)
    private int completionTokens;

    @Column(nullable = false)
    private int cacheHitTokens;

    @Column(nullable = false)
    private int totalTokens;

    @Column(length = 50)
    private String model;

    @Column(length = 20)
    private String messageType;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public TokenUsageEntity() {}

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getCacheHitTokens() { return cacheHitTokens; }
    public void setCacheHitTokens(int cacheHitTokens) { this.cacheHitTokens = cacheHitTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
