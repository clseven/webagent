package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.response.ActiveAgentRunResponse;
import com.example.sandbox.web.model.response.ActiveAgentRunEventResponse;
import com.example.sandbox.web.model.sse.SseEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理当前 JVM 内仍在执行的会话级 Agent 运行状态。
 *
 * <p>运行本身仍由 {@link AgentServiceImpl} 和 {@link ReactAgent} 执行；本服务只维护
 * 可供页面刷新和其他设备查询的运行快照及临时展示事件。事件不写入 {@code agent_run}，
 * 避免破坏运行账本只在收尾边界写入一次的约束；任务结束后仍以聊天历史为准。</p>
 */
@Service
public class ActiveAgentRunService {

    /** 按会话 ID 保存当前活动运行；同一会话只展示最近一次运行。 */
    private final ConcurrentMap<String, ActiveRunState> activeRuns = new ConcurrentHashMap<>();

    /**
     * 登记一次新的活动运行。
     *
     * @param sessionId 会话 ID
     * @return 新运行的只读快照
     */
    public ActiveAgentRunResponse start(String sessionId) {
        long now = System.currentTimeMillis();
        ActiveRunState state = new ActiveRunState(
                UUID.randomUUID().toString(), sessionId, "正在准备任务", now);
        activeRuns.put(sessionId, state);
        return state.toResponse();
    }

    /**
     * 缓存用户可见的流式事件并更新运行阶段。
     *
     * <p>心跳不会刷新阶段；完成、错误和中断事件会移除活动快照。方法不重试，
     * 因为内存状态更新不存在外部失败边界，缺失会话时直接忽略迟到事件。</p>
     *
     * @param sessionId 会话 ID
     * @param event     最新 SSE 事件
     */
    public void update(String sessionId, SseEvent event) {
        if (sessionId == null || event == null || event.type() == null) {
            return;
        }
        if ("done".equals(event.type()) || "error".equals(event.type())
                || "interrupted".equals(event.type())) {
            finish(sessionId);
            return;
        }
        ActiveRunState state = activeRuns.get(sessionId);
        if (state == null) {
            return;
        }
        if (!"heartbeat".equals(event.type())) {
            state.appendEvent(event);
        }
        String phase = resolvePhase(event);
        if (phase == null) {
            return;
        }
        state.updatePhase(phase);
    }

    /**
     * 查询会话当前的活动运行。
     *
     * @param sessionId 会话 ID
     * @return 存在时返回运行快照，否则为空
     */
    public Optional<ActiveAgentRunResponse> findActive(String sessionId) {
        ActiveRunState state = activeRuns.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(state.toResponse());
    }

    /**
     * 查询指定序号之后仍属于当前活动运行的展示事件。
     *
     * <p>客户端使用序号增量拉取，刷新后的第一次请求传 0 即可补回本轮已有过程。
     * 当前没有活动运行时返回空列表，调用方随后应回退到已持久化聊天历史。</p>
     *
     * @param sessionId    会话 ID
     * @param afterSequence 已消费的最后事件序号
     * @return 严格按序排列的增量事件
     */
    public List<ActiveAgentRunEventResponse> findEventsAfter(String sessionId, long afterSequence) {
        ActiveRunState state = activeRuns.get(sessionId);
        return state == null ? List.of() : state.eventsAfter(Math.max(0L, afterSequence));
    }

    /**
     * 标记会话运行结束并移除活动快照。
     *
     * @param sessionId 会话 ID
     */
    public void finish(String sessionId) {
        if (sessionId != null) {
            activeRuns.remove(sessionId);
        }
    }

    /**
     * 把后端事件映射成简短、稳定的前端阶段文案。
     *
     * @param event 最新流式事件
     * @return 可展示阶段；心跳等无需展示的事件返回 null
     */
    private String resolvePhase(SseEvent event) {
        Map<String, Object> data = event.data() != null ? event.data() : Map.of();
        return switch (event.type()) {
            case "plan" -> "正在规划任务";
            case "thinking_start", "reasoning_token" -> "正在思考";
            case "token" -> "正在生成回复";
            case "thinking_end" -> "正在处理下一步";
            case "tool_call", "tool_executing" -> toolPhase(data);
            case "observation" -> "正在分析工具结果";
            case "answer" -> "正在整理结果";
            case "status" -> truncate(String.valueOf(data.getOrDefault("message", "正在处理任务")), 80);
            default -> null;
        };
    }

    /**
     * 生成工具阶段文案，优先使用已有的用户可见行动说明。
     *
     * @param data SSE 事件数据
     * @return 工具执行阶段文案
     */
    private String toolPhase(Map<String, Object> data) {
        Object displayReason = data.get("displayReason");
        if (displayReason != null && !displayReason.toString().isBlank()) {
            return truncate(displayReason.toString(), 80);
        }
        Object tool = data.get("tool");
        return tool == null || tool.toString().isBlank()
                ? "正在调用工具"
                : "正在调用工具 " + truncate(tool.toString(), 48);
    }

    /**
     * 截断过长阶段文本，避免运行卡片被工具参数或状态正文撑开。
     *
     * @param value     原始文本
     * @param maxLength 最大字符数
     * @return 截断后的文本
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    /**
     * 单个会话活动运行的可变内存状态。
     */
    private static final class ActiveRunState {

        /** 服务端运行标识。 */
        private final String runId;

        /** 所属会话 ID。 */
        private final String sessionId;

        /** 运行开始时间。 */
        private final long startedAt;

        /** 当前用户可见阶段。 */
        private volatile String phase;

        /** 最近更新时间。 */
        private volatile long updatedAt;

        /** 本轮已产生的用户可见展示事件。 */
        private final List<ActiveAgentRunEventResponse> events = new ArrayList<>();

        /** 下一个展示事件序号。 */
        private long nextSequence = 1L;

        /**
         * 创建内存运行状态。
         *
         * @param runId    运行标识
         * @param sessionId 会话 ID
         * @param phase    初始阶段
         * @param startedAt 开始时间
         */
        private ActiveRunState(String runId, String sessionId, String phase, long startedAt) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.phase = phase;
            this.startedAt = startedAt;
            this.updatedAt = startedAt;
        }

        /**
         * 更新运行阶段及其时间戳。
         *
         * @param phase 新阶段文案
         */
        private void updatePhase(String phase) {
            this.phase = phase;
            this.updatedAt = System.currentTimeMillis();
        }

        /**
         * 按到达顺序追加一个可补播事件。
         *
         * <p>方法使用对象锁同时保护事件列表和序号，避免并发工具事件出现重复序号。
         * 事件数据复制到新的映射中，避免后续调用方修改原始 SSE 数据。</p>
         *
         * @param event 最新 SSE 事件
         */
        private synchronized void appendEvent(SseEvent event) {
            Map<String, Object> data = event.data() == null
                    ? Map.of()
                    : new LinkedHashMap<>(event.data());
            events.add(new ActiveAgentRunEventResponse(
                    runId, nextSequence++, event.type(), data, System.currentTimeMillis()));
        }

        /**
         * 复制指定序号之后的事件，避免把内部可变列表暴露给控制器线程。
         *
         * @param afterSequence 已消费的最后序号
         * @return 后续事件只读副本
         */
        private synchronized List<ActiveAgentRunEventResponse> eventsAfter(long afterSequence) {
            return events.stream()
                    .filter(event -> event.getSequence() > afterSequence)
                    .toList();
        }

        /**
         * 生成对外只读快照。
         *
         * @return 当前状态响应
         */
        private ActiveAgentRunResponse toResponse() {
            return new ActiveAgentRunResponse(runId, sessionId, phase, startedAt, updatedAt);
        }
    }
}
