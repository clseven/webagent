package com.example.sandbox.web.model.response;

import lombok.Getter;

/**
 * 当前会话活动 Agent 运行的只读快照。
 *
 * <p>该快照面向刷新恢复和跨设备查看，只暴露运行标识、阶段与时间信息，
 * 不承载模型推理正文或工具结果。</p>
 */
@Getter
public class ActiveAgentRunResponse {

    /** 本次服务端运行的唯一标识。 */
    private final String runId;

    /** 运行所属的会话 ID。 */
    private final String sessionId;

    /** 面向用户展示的当前运行阶段。 */
    private final String phase;

    /** 运行开始时间，Unix 毫秒时间戳。 */
    private final long startedAt;

    /** 最近一次阶段更新时间，Unix 毫秒时间戳。 */
    private final long updatedAt;

    /**
     * 创建活动运行快照。
     *
     * @param runId    服务端运行标识
     * @param sessionId 会话 ID
     * @param phase    当前运行阶段
     * @param startedAt 开始时间
     * @param updatedAt 最近更新时间
     */
    public ActiveAgentRunResponse(String runId, String sessionId, String phase,
                                  long startedAt, long updatedAt) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.phase = phase;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
    }
}
