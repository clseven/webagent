package com.example.sandbox.web.model.llm;

import java.util.List;

/**
 * 单次 Agent 运行的可持久化执行轨迹。
 *
 * <p>轨迹以一次用户请求为边界，保存规划、工具执行步骤和最终回答。工具调用步骤中的
 * reasoning_content 会随 {@link AgentStep} 保留；普通最终回答的完整推理不进入该结构。</p>
 *
 * @param version     轨迹结构版本
 * @param plan        本轮执行计划，可为空
 * @param steps       本轮工具执行步骤
 * @param finalAnswer 本轮最终回答或暂停提示
 */
public record AgentRunTrace(int version, String plan, List<AgentStep> steps, String finalAnswer) {

    /** 当前运行轨迹结构版本。 */
    public static final int CURRENT_VERSION = 1;

    /**
     * 创建当前版本的运行轨迹。
     *
     * @param plan        本轮执行计划，可为空
     * @param steps       本轮工具执行步骤，可为空
     * @param finalAnswer 本轮最终回答或暂停提示，可为空
     * @return 不可变运行轨迹
     */
    public static AgentRunTrace of(String plan, List<AgentStep> steps, String finalAnswer) {
        return new AgentRunTrace(
                CURRENT_VERSION,
                plan,
                steps != null ? List.copyOf(steps) : List.of(),
                finalAnswer);
    }
}
