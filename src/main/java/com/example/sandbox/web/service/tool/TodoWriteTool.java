package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.agent.AgentTodoUpdateResult;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.AgentTodoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 更新 Agent 运行时任务清单的工具。
 *
 * <h3>职责</h3>
 * <p>把模型显式提交的 todo 快照写入 {@link AgentTodoService}，并返回紧凑状态摘要。
 * 该工具不执行业务动作、不调度工具，也不负责并行批次执行。</p>
 */
@Component
public class TodoWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    /** TodoState 服务。 */
    private final AgentTodoService todoService;

    /**
     * 创建 todo_write 工具。
     *
     * @param todoService TodoState 服务
     */
    public TodoWriteTool(AgentTodoService todoService) {
        this.todoService = todoService;
    }

    /**
     * 获取工具定义。
     *
     * @return todo_write 的 function calling 定义
     */
    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "todo_write",
                "更新当前会话的运行时任务清单，记录目标、状态、成功信号、证据和阻塞原因；不执行任何业务动作。",
                parametersSchema(),
                "ALL"
        );
    }

    /**
     * 执行 TodoState 更新。
     *
     * <p>参数校验失败时返回错误字符串，避免异常冒泡打断 ReAct 循环。运行时异常会记录日志并返回
     * 失败 observation，让模型可据此修正参数或改计划。</p>
     *
     * @param sessionId 会话 ID
     * @param arguments LLM 传入的工具参数
     * @return 更新摘要或错误字符串
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            if (arguments == null) {
                return "错误：todo_write 参数无效 - arguments 不能为空";
            }
            String sourcePlan = optionalString(arguments.get("sourcePlan"));
            AgentTodoUpdateResult result = todoService.update(sessionId, sourcePlan, parseTodos(arguments.get("todos")));
            return result.getSummary();
        } catch (IllegalArgumentException e) {
            return "错误：todo_write 参数无效 - " + e.getMessage();
        } catch (Exception e) {
            log.error("TodoState 更新失败: sessionId={}", sessionId, e);
            return "错误：TodoState 更新失败 - " + e.getMessage();
        }
    }

    /**
     * 解析 todo 列表参数。
     *
     * @param rawTodos 原始 todos 参数
     * @return todo 条目列表
     */
    private List<AgentTodoItem> parseTodos(Object rawTodos) {
        if (!(rawTodos instanceof List<?> values)) {
            throw new IllegalArgumentException("todos 必须是数组");
        }
        List<AgentTodoItem> items = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("todos 每一项必须是对象");
            }
            items.add(parseTodo(rawMap));
        }
        return items;
    }

    /**
     * 解析单个 todo 对象。
     *
     * @param rawMap 原始 todo map
     * @return todo 条目
     */
    private AgentTodoItem parseTodo(Map<?, ?> rawMap) {
        return new AgentTodoItem(
                requiredString(rawMap, "id"),
                requiredString(rawMap, "title"),
                AgentTodoStatus.fromWireValue(rawMap.get("status")),
                stringList(rawMap.get("successSignals")),
                stringList(rawMap.get("evidence")),
                optionalString(rawMap.get("blocker")),
                optionalString(rawMap.get("reason")),
                stringList(rawMap.get("batchIds"))
        );
    }

    /**
     * 读取必填字符串字段。
     *
     * @param map   原始 map
     * @param field 字段名
     * @return 非空字符串
     */
    private String requiredString(Map<?, ?> map, String field) {
        String value = optionalString(map.get(field));
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 读取可选字符串字段。
     *
     * @param value 原始值
     * @return 非空字符串或 null
     */
    private String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 读取字符串数组字段。
     *
     * @param value 原始值
     * @return 字符串列表；缺失时为空列表
     */
    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("列表字段必须是数组");
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = optionalString(item);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    /**
     * 构建工具参数 schema。
     *
     * @return JSON Schema map
     */
    private Map<String, Object> parametersSchema() {
        Map<String, Object> todoProperties = new LinkedHashMap<>();
        todoProperties.put("id", Map.of("type", "string", "description", "稳定 todo ID，更新同一目标时必须复用。"));
        todoProperties.put("title", Map.of("type", "string", "description", "用户可读目标标题，只描述目标不描述微动作。"));
        todoProperties.put("status", Map.of(
                "type", "string",
                "enum", List.of("pending", "in_progress", "completed", "blocked", "cancelled"),
                "description", "todo 当前状态。"));
        todoProperties.put("successSignals", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "判断该目标完成所需的成功信号。"));
        todoProperties.put("evidence", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "支撑 completed 状态的工具 observation、用户确认或可验证输出。"));
        todoProperties.put("blocker", Map.of("type", "string", "description", "blocked 状态必填，说明不可继续的外部原因。"));
        todoProperties.put("reason", Map.of("type", "string", "description", "cancelled 状态必填，说明取消原因。"));
        todoProperties.put("batchIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "与该 todo 相关的工具批次 ID；第一版只记录引用。"));

        Map<String, Object> todoSchema = new LinkedHashMap<>();
        todoSchema.put("type", "object");
        todoSchema.put("properties", todoProperties);
        todoSchema.put("required", List.of("id", "title", "status"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourcePlan", Map.of("type", "string", "description", "PlanAgent 产出的任务模型摘要，可选。"));
        properties.put("todos", Map.of(
                "type", "array",
                "description", "当前会话的完整 todo 快照。",
                "items", todoSchema));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("todos"));
        return schema;
    }
}
