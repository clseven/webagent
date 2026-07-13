package com.example.sandbox.web.model.llm;

/**
 * Agent 单次运行的持久化状态。
 *
 * <p>该状态用于区分正常助手回复与可继续的暂停运行，避免把执行边界提示
 * 当成模型可见的最终任务结论。</p>
 */
public enum AgentRunStatus {

    /** Agent 已正常生成最终回答。 */
    COMPLETED,

    /** Agent 达到最大迭代次数，保留检查点等待后续消息继续。 */
    PAUSED_MAX_ITERATIONS
}
