package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证最终回答门禁的两层把关：TodoState 前置硬信号 + 基于证据的模型自检。
 *
 * <p>前置层：未闭环 / 缺证据直接拦，且优先于候选答案自检。
 * 自检层：TodoState 干净时返回“保存候选并验证”决策，候选是否通过由 {@link ReactAgent}
 * 根据下一轮行为判断。</p>
 */
class FinalTodoGuardHookTest {

    /** 会话 ID。 */
    private static final String SESSION = "session-1";

    // ==================== 第一层：TodoState 前置硬信号 ====================

    /**
     * 存在未完成 todo 时始终前置拦截，不能因为重复收尾而绕过 TodoState。
     */
    @Test
    void 未完成Todo前置拦截且优先于放行阈值() {
        AgentTodoService service = new AgentTodoService();
        service.update(SESSION, "plan", List.of(
                item("inspect", "确认 Hook 接入点", AgentTodoStatus.COMPLETED,
                        List.of("找到接入点"), List.of("StopHook 已定位"), null),
                item("guard", "实现最终门禁", AgentTodoStatus.IN_PROGRESS,
                        List.of("未完成时阻止最终回答"), List.of(), null)
        ));
        FinalTodoGuardHook hook = new FinalTodoGuardHook(service, SESSION);

        ReactAgent.StopDecision first = hook.run(List.of(ChatMessage.userMessage("请实现")), 1);
        ReactAgent.StopDecision later = hook.run(List.of(ChatMessage.userMessage("请实现")), 99);

        assertThat(first.action()).isEqualTo(ReactAgent.StopAction.CONTINUE);
        assertThat(first.message())
                .contains("TodoState 最终门禁")
                .contains("guard：实现最终门禁")
                .contains("调用 todo_write")
                .contains("blocked");
        assertThat(later.action()).isEqualTo(ReactAgent.StopAction.CONTINUE);
        assertThat(later.message()).contains("TodoState 最终门禁");
    }

    /**
     * completed 缺少 evidence 时前置拦截，要求补证据或修正状态。
     */
    @Test
    void completed缺Evidence前置拦截() {
        AgentTodoService service = new AgentTodoService();
        service.update(SESSION, "plan", List.of(
                item("verify", "运行验证", AgentTodoStatus.COMPLETED,
                        List.of("测试通过"), List.of(), null)
        ));

        ReactAgent.StopDecision decision = new FinalTodoGuardHook(service, SESSION)
                .run(List.of(ChatMessage.userMessage("请实现")), 1);

        assertThat(decision.action()).isEqualTo(ReactAgent.StopAction.CONTINUE);
        assertThat(decision.message())
                .contains("completed todo 缺少 evidence")
                .contains("verify：运行验证");
    }

    // ==================== 第二层：证据自检 ====================

    /**
     * 无 TodoState 的轻量任务不再直接放行：第 1 次收尾注入证据自检提示。
     */
    @Test
    void 无TodoState第1次收尾注入自检() {
        FinalTodoGuardHook hook = new FinalTodoGuardHook(new AgentTodoService(), SESSION);

        ReactAgent.StopDecision decision = hook.run(List.of(ChatMessage.userMessage("你好")), 1);

        assertThat(decision.action()).isEqualTo(ReactAgent.StopAction.VERIFY_CANDIDATE);
        assertThat(decision.message())
                .contains("回答前自检")
                .contains("原样放行")
                .contains("不得重新措辞");
    }

    /**
     * TodoState 已闭环（completed 有证据 / blocked 有 blocker）时进入候选答案验证。
     */
    @Test
    void todoState闭环后保存候选并进入自检() {
        AgentTodoService service = new AgentTodoService();
        service.update(SESSION, "plan", List.of(
                item("done", "实现工具", AgentTodoStatus.COMPLETED,
                        List.of("工具可用"), List.of("todo_write 返回更新摘要"), null),
                item("wait", "等待用户确认", AgentTodoStatus.BLOCKED,
                        List.of("用户确认"), List.of(), "需要用户确认是否继续提交")
        ));
        FinalTodoGuardHook hook = new FinalTodoGuardHook(service, SESSION);

        ReactAgent.StopDecision decision = hook.run(List.of(ChatMessage.userMessage("请实现")), 1);
        assertThat(decision.action()).isEqualTo(ReactAgent.StopAction.VERIFY_CANDIDATE);
        assertThat(decision.message()).contains("回答前自检");
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
