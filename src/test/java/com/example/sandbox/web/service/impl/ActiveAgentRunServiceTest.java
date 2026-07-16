package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.sse.SseEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动 Agent 运行快照服务测试。
 */
class ActiveAgentRunServiceTest {

    /**
     * 验证运行开始后可查询同一开始时间，并能根据流式事件更新阶段。
     */
    @Test
    void exposesStartedRunAndUpdatesPhase() {
        ActiveAgentRunService service = new ActiveAgentRunService();

        var started = service.start("session-1");
        service.update("session-1", SseEvent.toolCall(
                "web_search", Map.of("query", "current docs"), 1, "正在查询官方资料"));

        var active = service.findActive("session-1").orElseThrow();
        assertThat(active.getRunId()).isEqualTo(started.getRunId());
        assertThat(active.getStartedAt()).isEqualTo(started.getStartedAt());
        assertThat(active.getPhase()).isEqualTo("正在查询官方资料");
        assertThat(active.getUpdatedAt()).isGreaterThanOrEqualTo(active.getStartedAt());
    }

    /**
     * 验证刷新后的客户端可以从序号 0 补回事件，并在后续只拉取新的增量。
     */
    @Test
    void replaysDisplayEventsAfterClientSequence() {
        ActiveAgentRunService service = new ActiveAgentRunService();
        var started = service.start("session-1");

        service.update("session-1", SseEvent.reasoningToken("先检查页面"));
        service.update("session-1", SseEvent.toolCall(
                "browser_inspect", Map.of("max_elements", 20), 1, "正在检查网页内容"));

        var allEvents = service.findEventsAfter("session-1", 0);
        assertThat(allEvents).hasSize(2);
        assertThat(allEvents).extracting("runId").containsOnly(started.getRunId());
        assertThat(allEvents).extracting("sequence").containsExactly(1L, 2L);
        assertThat(allEvents).extracting("type")
                .containsExactly("reasoning_token", "tool_call");

        var incremental = service.findEventsAfter("session-1", 1);
        assertThat(incremental).hasSize(1);
        assertThat(incremental.get(0).getSequence()).isEqualTo(2L);
        assertThat(incremental.get(0).getData().get("tool")).isEqualTo("browser_inspect");
    }

    /**
     * 验证终端事件会清除活动快照，避免页面完成后继续显示转圈。
     */
    @Test
    void removesRunWhenTerminalEventArrives() {
        ActiveAgentRunService service = new ActiveAgentRunService();
        service.start("session-1");

        service.update("session-1", SseEvent.done(2, null));

        assertThat(service.findActive("session-1")).isEmpty();
    }
}
