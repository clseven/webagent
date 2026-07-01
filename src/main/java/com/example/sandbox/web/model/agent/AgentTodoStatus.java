package com.example.sandbox.web.model.agent;

import java.util.Locale;

/**
 * Agent 运行时 todo 的状态枚举。
 *
 * <h3>用途</h3>
 * <p>用于描述当前会话内任务清单条目的推进状态，并保持与 todo_write 工具协议中的
 * 小写字符串值一致。</p>
 */
public enum AgentTodoStatus {

    /** 已列入计划但尚未开始。 */
    PENDING("pending"),

    /** 当前正在推进。 */
    IN_PROGRESS("in_progress"),

    /** 已有证据支撑完成。 */
    COMPLETED("completed"),

    /** 因用户、权限、环境或外部信息缺失而阻塞。 */
    BLOCKED("blocked"),

    /** 因计划调整而不再需要。 */
    CANCELLED("cancelled");

    /** 工具协议中的稳定字符串值。 */
    private final String wireValue;

    /**
     * 创建状态枚举。
     *
     * @param wireValue 工具协议中的小写状态值
     */
    AgentTodoStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 获取工具协议中的状态值。
     *
     * @return 小写状态值
     */
    public String getWireValue() {
        return wireValue;
    }

    /**
     * 从工具参数中的字符串解析 todo 状态。
     *
     * @param value 工具参数传入的状态值
     * @return 匹配的状态枚举
     * @throws IllegalArgumentException 状态缺失或不在允许集合内时抛出
     */
    public static AgentTodoStatus fromWireValue(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (AgentTodoStatus status : values()) {
            if (status.wireValue.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("status 非法：" + raw);
    }
}
