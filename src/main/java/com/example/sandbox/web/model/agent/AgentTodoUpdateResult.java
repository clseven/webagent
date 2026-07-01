package com.example.sandbox.web.model.agent;

import java.util.List;

/**
 * TodoState 更新结果。
 *
 * <h3>用途</h3>
 * <p>同时返回最新状态、模型可读摘要和非致命提醒。第一版允许 completed 缺少 evidence，
 * 但会通过 warnings 提醒模型补证据。</p>
 */
public class AgentTodoUpdateResult {

    /** 最新 TodoState。 */
    private final AgentTodoState state;

    /** 非致命提醒列表。 */
    private final List<String> warnings;

    /** 给 LLM 和工具 observation 使用的紧凑摘要。 */
    private final String summary;

    /**
     * 创建更新结果。
     *
     * @param state    最新状态
     * @param warnings 非致命提醒
     * @param summary  紧凑摘要
     */
    public AgentTodoUpdateResult(AgentTodoState state, List<String> warnings, String summary) {
        this.state = state;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        this.summary = summary;
    }

    /**
     * 获取最新状态。
     *
     * @return TodoState
     */
    public AgentTodoState getState() {
        return state;
    }

    /**
     * 获取非致命提醒。
     *
     * @return 不可变提醒列表
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * 获取紧凑摘要。
     *
     * @return 摘要文本
     */
    public String getSummary() {
        return summary;
    }
}
