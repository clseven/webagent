package com.example.sandbox.web.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageEntity {

    /**
     * 消息主键 ID。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 消息所属会话。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSessionEntity session;

    /**
     * 消息角色，例如 user、assistant、tool。
     */
    @Column(name = "role", length = 32, nullable = false)
    private String role;

    /**
     * 消息正文内容。
     */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * 思考链内容（reasoning_content，部分模型支持）
     */
    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    /**
     * assistant 消息对应的过程事件 JSON。
     *
     * <p>这里保存前端历史展示需要的 plan、thinking、reasoning、toolResult 等事件。
     * 字段只作为展示快照使用，不参与后续 LLM 上下文组装。</p>
     */
    @Column(name = "events_json", columnDefinition = "LONGTEXT")
    private String eventsJson;

    /**
     * Agent 运行状态；普通历史消息允许为空。
     *
     * <p>当值为 {@code PAUSED_MAX_ITERATIONS} 时，下一轮应优先恢复检查点，
     * 不把超限展示文案当作最终任务结论。</p>
     */
    @Column(name = "run_status", length = 32)
    private String runStatus;

    /**
     * Agent 暂停时的协议级模型消息检查点 JSON。
     *
     * <p>该字段与仅供前端展示的 {@link #eventsJson} 分离，保存 tool_call ID、
     * 并发调用分组及 tool 结果消息顺序。</p>
     */
    @Column(name = "checkpoint_json", columnDefinition = "LONGTEXT")
    private String checkpointJson;

    /**
     * 消息创建时间戳，单位毫秒。
     */
    @Column(name = "timestamp")
    private Long timestamp;
}
