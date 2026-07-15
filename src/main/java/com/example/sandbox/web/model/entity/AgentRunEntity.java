package com.example.sandbox.web.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 单次用户请求对应的 Agent 运行记录。
 *
 * <p>每次用户请求最多创建一条记录，运行过程不会按工具步骤拆成多行。轨迹在运行完成、
 * 达到执行上限或用户主动中断时一次写入，用于后续上下文构建和运行审计。</p>
 */
@Entity
@Table(
        name = "agent_run",
        indexes = @Index(name = "idx_agent_run_session_created", columnList = "session_id, created_at")
)
@Getter
@Setter
public class AgentRunEntity {

    /** 运行记录主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 运行所属会话。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSessionEntity session;

    /** 运行结束状态。 */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    /** 带版本号的运行轨迹 JSON。 */
    @Column(name = "trace_json", columnDefinition = "LONGTEXT", nullable = false)
    private String traceJson;

    /** 本轮可供后续模型继续使用的完整协议 JSON。 */
    @Column(name = "protocol_json", columnDefinition = "LONGTEXT")
    private String protocolJson;

    /** 本轮协议的估算 token。 */
    @Column(name = "protocol_tokens", nullable = false)
    private int protocolTokens;

    /** 本轮 ReAct 迭代次数。 */
    @Column(name = "iterations", nullable = false)
    private int iterations;

    /** 本轮提示 token 数。 */
    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    /** 本轮生成 token 数。 */
    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    /** 本轮缓存命中 token 数。 */
    @Column(name = "cache_hit_tokens", nullable = false)
    private int cacheHitTokens;

    /** 本轮总 token 数。 */
    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    /** 运行记录创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 创建尚未填充业务字段的运行实体，并初始化创建时间。
     */
    public AgentRunEntity() {
        this.createdAt = LocalDateTime.now();
    }
}
