package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;

/**
 * 消息 Entity
 *
 * @author example
 * @date 2026/05/14
 */
@Entity
@Table(name = "chat_message", indexes = {
    @Index(name = "idx_session_id", columnList = "session_id"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSessionEntity session;

    @Column(name = "role", length = 32, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * 思考链内容（reasoning_content，部分模型支持）
     */
    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "timestamp")
    private Long timestamp;

    public ChatMessageEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ConversationSessionEntity getSession() {
        return session;
    }

    public void setSession(ConversationSessionEntity session) {
        this.session = session;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
