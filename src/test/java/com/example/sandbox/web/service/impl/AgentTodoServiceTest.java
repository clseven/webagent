package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.agent.AgentTodoUpdateResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Agent 会话内 TodoState 的状态校验和摘要生成。
 */
class AgentTodoServiceTest {

    /**
     * 验证服务会按会话保存 TodoState，并生成给模型读取的紧凑摘要。
     */
    @Test
    void update会保存状态并生成摘要() {
        AgentTodoService service = new AgentTodoService();

        AgentTodoUpdateResult result = service.update("session-1", "读取项目并实现 TodoWrite", List.of(
                item("read-plan", "读取设计计划", AgentTodoStatus.COMPLETED,
                        List.of("确认目标"), List.of("已读取 2026-07-01 设计计划"), null, null),
                item("implement-runtime", "实现运行时 TodoState", AgentTodoStatus.IN_PROGRESS,
                        List.of("新增内存状态", "接入 todo_write"), List.of(), null, null)
        ));

        assertThat(result.getSummary())
                .contains("TodoState 已更新：2 项")
                .contains("1 completed")
                .contains("1 in_progress")
                .contains("当前进行中：实现运行时 TodoState");
        assertThat(result.getState().getSourcePlan()).contains("TodoWrite");
        assertThat(service.get("session-1")).hasValueSatisfying(state ->
                assertThat(state.getItems()).extracting(AgentTodoItem::getId)
                        .containsExactly("read-plan", "implement-runtime"));
    }

    /**
     * 验证同一会话同一时刻只能有一个主进行中 todo。
     */
    @Test
    void update拒绝多个进行中Todo() {
        AgentTodoService service = new AgentTodoService();

        assertThatThrownBy(() -> service.update("session-1", "plan", List.of(
                item("a", "任务 A", AgentTodoStatus.IN_PROGRESS,
                        List.of("完成 A"), List.of(), null, null),
                item("b", "任务 B", AgentTodoStatus.IN_PROGRESS,
                        List.of("完成 B"), List.of(), null, null)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一时间最多一个 in_progress todo");
    }

    /**
     * 验证 blocked 状态必须说明阻塞原因，避免模型用 blocked 掩盖未完成事项。
     */
    @Test
    void blocked必须包含阻塞原因() {
        AgentTodoService service = new AgentTodoService();

        assertThatThrownBy(() -> service.update("session-1", "plan", List.of(
                item("confirm", "等待用户确认", AgentTodoStatus.BLOCKED,
                        List.of("拿到确认"), List.of(), null, null)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked todo 必须包含 blocker");
    }

    /**
     * 验证 completed 缺少证据时先返回提醒但仍保存状态，便于第一版渐进收紧。
     */
    @Test
    void completed缺少Evidence时返回提醒() {
        AgentTodoService service = new AgentTodoService();

        AgentTodoUpdateResult result = service.update("session-1", "plan", List.of(
                item("verify", "验证实现", AgentTodoStatus.COMPLETED,
                        List.of("测试通过"), List.of(), null, null)
        ));

        assertThat(result.getWarnings())
                .containsExactly("completed todo 缺少 evidence：verify");
        assertThat(result.getSummary()).contains("提醒：completed todo 缺少 evidence：verify");
    }

    /**
     * 验证未完成 todo 不能被下一次更新静默删除，必须改为 cancelled 并说明原因。
     */
    @Test
    void update拒绝静默删除未完成Todo() {
        AgentTodoService service = new AgentTodoService();
        service.update("session-1", "plan", List.of(
                item("read", "读取计划", AgentTodoStatus.COMPLETED,
                        List.of("已读取"), List.of("计划文档已读取"), null, null),
                item("verify", "运行验证", AgentTodoStatus.PENDING,
                        List.of("测试通过"), List.of(), null, null)
        ));

        assertThatThrownBy(() -> service.update("session-1", "plan", List.of(
                item("read", "读取计划", AgentTodoStatus.COMPLETED,
                        List.of("已读取"), List.of("计划文档已读取"), null, null)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能删除未完成 todo：verify");
    }

    /**
     * 创建测试用 todo 条目。
     *
     * @param id             稳定 ID
     * @param title          用户可读标题
     * @param status         当前状态
     * @param successSignals 成功信号
     * @param evidence       完成证据
     * @param blocker        阻塞原因
     * @param reason         取消原因
     * @return todo 条目
     */
    private AgentTodoItem item(String id, String title, AgentTodoStatus status,
                               List<String> successSignals, List<String> evidence,
                               String blocker, String reason) {
        return new AgentTodoItem(id, title, status, successSignals, evidence,
                blocker, reason, List.of());
    }
}
