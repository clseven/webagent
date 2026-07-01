package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.impl.AgentTodoService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 todo_write 工具的参数协议和服务接入。
 */
class TodoWriteToolTest {

    /**
     * 验证工具会解析 LLM 传入的 Map 参数并更新当前会话 TodoState。
     */
    @Test
    void execute会解析Todos并更新服务() {
        AgentTodoService service = new AgentTodoService();
        TodoWriteTool tool = new TodoWriteTool(service);

        String result = tool.execute("session-1", Map.of(
                "sourcePlan", "完成 TodoWrite 第一阶段",
                "todos", List.of(
                        todo("read-plan", "读取设计计划", "completed",
                                List.of("确认目标"), List.of("已读取设计计划")),
                        todo("implement", "实现运行时状态", "in_progress",
                                List.of("服务可保存状态"), List.of())
                )
        ));

        assertThat(result)
                .contains("TodoState 已更新：2 项")
                .contains("当前进行中：实现运行时状态");
        assertThat(service.get("session-1")).hasValueSatisfying(state -> {
            assertThat(state.getSourcePlan()).contains("第一阶段");
            assertThat(state.getItems()).extracting(item -> item.getStatus())
                    .containsExactly(AgentTodoStatus.COMPLETED, AgentTodoStatus.IN_PROGRESS);
        });
    }

    /**
     * 验证 blocked 缺少 blocker 时返回工具层错误字符串，不让异常冒泡打断 ReAct 循环。
     */
    @Test
    void execute参数非法时返回错误字符串() {
        TodoWriteTool tool = new TodoWriteTool(new AgentTodoService());

        String result = tool.execute("session-1", Map.of(
                "todos", List.of(todo("blocked", "等待外部条件", "blocked",
                        List.of("条件可用"), List.of()))
        ));

        assertThat(result)
                .startsWith("错误：todo_write 参数无效 - ")
                .contains("blocked todo 必须包含 blocker");
    }

    /**
     * 验证工具定义包含完整 schema，并作为 ALL 工具进入不同沙箱类型。
     */
    @Test
    void definition包含TodosSchema并适用于全部沙箱() {
        ToolDefinition definition = new TodoWriteTool(new AgentTodoService()).getDefinition();

        assertThat(definition.getName()).isEqualTo("todo_write");
        assertThat(definition.getSandboxType()).isEqualTo("ALL");
        assertThat(definition.getDescription()).contains("运行时任务清单");
        assertThat(definition.getParameters()).containsEntry("type", "object");
        assertThat(definition.getParameters()).containsKey("required");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) definition.getParameters().get("properties");
        assertThat(properties).containsKey("todos");
    }

    /**
     * 构造测试用 todo 参数。
     *
     * @param id             稳定 ID
     * @param title          标题
     * @param status         字符串状态
     * @param successSignals 成功信号
     * @param evidence       完成证据
     * @return 工具参数 Map
     */
    private Map<String, Object> todo(String id, String title, String status,
                                     List<String> successSignals, List<String> evidence) {
        return Map.of(
                "id", id,
                "title", title,
                "status", status,
                "successSignals", successSignals,
                "evidence", evidence
        );
    }
}
