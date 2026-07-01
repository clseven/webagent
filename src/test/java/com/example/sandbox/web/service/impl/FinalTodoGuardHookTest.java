package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证最终回答门禁会在 TodoState 未闭环时强制 ReAct 循环继续。
 */
class FinalTodoGuardHookTest {

    /**
     * 没有 TodoState 的轻量任务不应被门禁拦截。
     */
    @Test
    void noTodoState时允许最终回答() {
        FinalTodoGuardHook hook = new FinalTodoGuardHook(new AgentTodoService(), "session-1");

        assertThat(hook.run(List.of(ChatMessage.userMessage("你好")))).isNull();
    }

    /**
     * 存在 pending 或 in_progress todo 时，最终回答前必须继续执行或显式标记 blocked。
     */
    @Test
    void 存在未完成Todo时返回继续执行提醒() {
        AgentTodoService service = new AgentTodoService();
        service.update("session-1", "plan", List.of(
                item("inspect", "确认 Hook 接入点", AgentTodoStatus.COMPLETED,
                        List.of("找到接入点"), List.of("ReactAgentStopHook 已定位"), null),
                item("guard", "实现最终门禁", AgentTodoStatus.IN_PROGRESS,
                        List.of("未完成时阻止最终回答"), List.of(), null)
        ));

        String reminder = new FinalTodoGuardHook(service, "session-1")
                .run(List.of(ChatMessage.userMessage("请实现")));

        assertThat(reminder)
                .contains("TodoState 最终门禁")
                .contains("guard：实现最终门禁")
                .contains("调用 todo_write")
                .contains("blocked");
    }

    /**
     * completed 缺少 evidence 时应继续循环，让执行器补证据或修正状态。
     */
    @Test
    void completed缺少Evidence时返回补证据提醒() {
        AgentTodoService service = new AgentTodoService();
        service.update("session-1", "plan", List.of(
                item("verify", "运行验证", AgentTodoStatus.COMPLETED,
                        List.of("测试通过"), List.of(), null)
        ));

        String reminder = new FinalTodoGuardHook(service, "session-1")
                .run(List.of(ChatMessage.userMessage("请实现")));

        assertThat(reminder)
                .contains("completed todo 缺少 evidence")
                .contains("verify：运行验证");
    }

    /**
     * 全部 todo 已完成且有证据，或已合理阻塞时，门禁允许最终回答。
     */
    @Test
    void 全部完成或阻塞时允许最终回答() {
        AgentTodoService service = new AgentTodoService();
        service.update("session-1", "plan", List.of(
                item("done", "实现工具", AgentTodoStatus.COMPLETED,
                        List.of("工具可用"), List.of("todo_write 返回更新摘要"), null),
                item("wait", "等待用户确认", AgentTodoStatus.BLOCKED,
                        List.of("用户确认"), List.of(), "需要用户确认是否继续提交")
        ));

        String reminder = new FinalTodoGuardHook(service, "session-1")
                .run(List.of(ChatMessage.userMessage("请实现")));

        assertThat(reminder).isNull();
    }

    /**
     * 创建测试用 todo 条目。
     *
     * @param id             稳定 ID
     * @param title          标题
     * @param status         状态
     * @param successSignals 成功信号
     * @param evidence       证据
     * @param blocker        阻塞原因
     * @return todo 条目
     */
    private AgentTodoItem item(String id, String title, AgentTodoStatus status,
                               List<String> successSignals, List<String> evidence, String blocker) {
        return new AgentTodoItem(id, title, status, successSignals, evidence,
                blocker, null, List.of());
    }
}
