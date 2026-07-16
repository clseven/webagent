package com.example.sandbox.web.model.response;

import lombok.Getter;

import java.util.Map;

/**
 * 活动 Agent 运行中可供刷新页面补播的单个展示事件。
 *
 * <p>事件只在当前服务进程内临时保存，结构与聊天 SSE 的用户可见事件保持一致，
 * 让同一用户在刷新页面或更换设备后可以按序恢复文字和工具过程。</p>
 */
@Getter
public class ActiveAgentRunEventResponse {

    /** 所属服务端运行标识。 */
    private final String runId;

    /** 当前运行内严格递增的事件序号。 */
    private final long sequence;

    /** SSE 事件类型。 */
    private final String type;

    /** SSE 事件的用户可见数据。 */
    private final Map<String, Object> data;

    /** 服务端收到事件的 Unix 毫秒时间戳。 */
    private final long createdAt;

    /**
     * 创建活动运行展示事件。
     *
     * @param runId    服务端运行标识
     * @param sequence 运行内事件序号
     * @param type     SSE 事件类型
     * @param data     SSE 事件数据
     * @param createdAt 服务端接收时间
     */
    public ActiveAgentRunEventResponse(String runId, long sequence, String type,
                                       Map<String, Object> data, long createdAt) {
        this.runId = runId;
        this.sequence = sequence;
        this.type = type;
        this.data = data;
        this.createdAt = createdAt;
    }
}
