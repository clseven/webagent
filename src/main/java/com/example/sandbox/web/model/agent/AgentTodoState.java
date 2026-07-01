package com.example.sandbox.web.model.agent;

import java.util.List;

/**
 * 单个会话当前轮的 Agent TodoState。
 *
 * <h3>职责</h3>
 * <p>保存运行时任务清单和来源计划摘要。第一版只存放在内存中，不跨会话持久化，
 * 也不负责调度工具或并行批次。</p>
 */
public class AgentTodoState {

    /** 会话 ID。 */
    private final String sessionId;

    /** PlanAgent 产出的任务模型摘要，可为空。 */
    private final String sourcePlan;

    /** 当前 todo 条目快照。 */
    private final List<AgentTodoItem> items;

    /** 最近更新时间戳，毫秒。 */
    private final long updatedAt;

    /**
     * 创建 TodoState 快照。
     *
     * @param sessionId  会话 ID
     * @param sourcePlan 来源计划摘要，可为空
     * @param items      todo 条目
     * @param updatedAt  更新时间戳
     */
    public AgentTodoState(String sessionId, String sourcePlan, List<AgentTodoItem> items, long updatedAt) {
        this.sessionId = sessionId;
        this.sourcePlan = sourcePlan;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.updatedAt = updatedAt;
    }

    /**
     * 获取会话 ID。
     *
     * @return 会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取来源计划摘要。
     *
     * @return 来源计划摘要，可为空
     */
    public String getSourcePlan() {
        return sourcePlan;
    }

    /**
     * 获取 todo 条目。
     *
     * @return 不可变条目列表
     */
    public List<AgentTodoItem> getItems() {
        return items;
    }

    /**
     * 获取更新时间戳。
     *
     * @return 毫秒时间戳
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 统计指定状态的 todo 数量。
     *
     * @param status 待统计状态
     * @return 匹配数量
     */
    public long count(AgentTodoStatus status) {
        return items.stream().filter(item -> item.getStatus() == status).count();
    }

    /**
     * 查找仍需推进的 todo。
     *
     * @return pending 与 in_progress 条目列表
     */
    public List<AgentTodoItem> openItems() {
        return items.stream().filter(AgentTodoItem::isOpen).toList();
    }

    /**
     * 查找缺少证据的 completed todo。
     *
     * @return completed 但 evidence 为空的条目列表
     */
    public List<AgentTodoItem> completedWithoutEvidence() {
        return items.stream().filter(AgentTodoItem::isCompletedWithoutEvidence).toList();
    }
}
