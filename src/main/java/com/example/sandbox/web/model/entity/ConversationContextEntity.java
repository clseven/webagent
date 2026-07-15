package com.example.sandbox.web.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话级模型上下文快照。
 *
 * <p>每个会话最多一条，保存有界长期摘要和最近完整模型协议。该实体是可重建快照，
 * 原始运行事实仍以 {@link AgentRunEntity} 为准。</p>
 */
@Entity
@Table(name = "conversation_context")
@Getter
@Setter
public class ConversationContextEntity {

    /** 会话 ID，同时作为上下文快照主键。 */
    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** 上下文所属会话。 */
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "session_id")
    private ConversationSessionEntity session;

    /** 带版本号的长期摘要 JSON。 */
    @Column(name = "summary_json", columnDefinition = "LONGTEXT")
    private String summaryJson;

    /** 最近完整模型协议 JSON。 */
    @Column(name = "recent_protocol_json", columnDefinition = "LONGTEXT")
    private String recentProtocolJson;

    /** 最近协议的估算 token。 */
    @Column(name = "recent_protocol_tokens", nullable = false)
    private int recentProtocolTokens;

    /** 已经应用到快照的最后一条 Agent 运行 ID。 */
    @Column(name = "last_applied_run_id")
    private Long lastAppliedRunId;

    /** 乐观锁版本，防止并发请求覆盖上下文。 */
    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    /** 快照最后更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 创建空上下文快照并初始化更新时间。 */
    public ConversationContextEntity() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 刷新上下文快照更新时间。 */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
