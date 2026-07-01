package com.example.sandbox.web.model.agent;

import java.util.List;

/**
 * Agent 会话内的单个 todo 条目。
 *
 * <h3>职责</h3>
 * <p>保存一个可验收目标的标题、状态、成功信号、完成证据、阻塞原因和批次引用。
 * 该对象只表达目标状态，不表达工具调度指令。</p>
 */
public class AgentTodoItem {

    /** 稳定 ID，模型更新同一目标时必须复用。 */
    private final String id;

    /** 用户可读的目标标题。 */
    private final String title;

    /** 当前推进状态。 */
    private final AgentTodoStatus status;

    /** 判断该目标完成所需的成功信号。 */
    private final List<String> successSignals;

    /** 支撑 completed 状态的观察证据。 */
    private final List<String> evidence;

    /** blocked 状态下需要用户、权限、环境或外部信息补足的原因。 */
    private final String blocker;

    /** cancelled 状态下取消该目标的原因。 */
    private final String reason;

    /** 与该 todo 相关的并行批次 ID；第一版只记录引用，不调度批次。 */
    private final List<String> batchIds;

    /**
     * 创建 todo 条目。
     *
     * @param id             稳定 ID
     * @param title          用户可读标题
     * @param status         当前状态
     * @param successSignals 成功信号列表
     * @param evidence       完成证据列表
     * @param blocker        阻塞原因，可为空
     * @param reason         取消原因，可为空
     * @param batchIds       相关批次 ID，可为空
     */
    public AgentTodoItem(String id, String title, AgentTodoStatus status,
                         List<String> successSignals, List<String> evidence,
                         String blocker, String reason, List<String> batchIds) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.successSignals = copyOrEmpty(successSignals);
        this.evidence = copyOrEmpty(evidence);
        this.blocker = normalizeBlank(blocker);
        this.reason = normalizeBlank(reason);
        this.batchIds = copyOrEmpty(batchIds);
    }

    /**
     * 获取稳定 ID。
     *
     * @return todo ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取用户可读标题。
     *
     * @return todo 标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取当前状态。
     *
     * @return todo 状态
     */
    public AgentTodoStatus getStatus() {
        return status;
    }

    /**
     * 获取成功信号。
     *
     * @return 不可变成功信号列表
     */
    public List<String> getSuccessSignals() {
        return successSignals;
    }

    /**
     * 获取完成证据。
     *
     * @return 不可变证据列表
     */
    public List<String> getEvidence() {
        return evidence;
    }

    /**
     * 获取阻塞原因。
     *
     * @return 阻塞原因；未阻塞时为 null
     */
    public String getBlocker() {
        return blocker;
    }

    /**
     * 获取取消原因。
     *
     * @return 取消原因；未取消时为 null
     */
    public String getReason() {
        return reason;
    }

    /**
     * 获取相关批次 ID。
     *
     * @return 不可变批次 ID 列表
     */
    public List<String> getBatchIds() {
        return batchIds;
    }

    /**
     * 判断该 todo 是否仍需要继续推进。
     *
     * @return true 表示状态为 pending 或 in_progress
     */
    public boolean isOpen() {
        return status == AgentTodoStatus.PENDING || status == AgentTodoStatus.IN_PROGRESS;
    }

    /**
     * 判断 completed 状态是否缺少证据。
     *
     * @return true 表示状态为 completed 且 evidence 为空
     */
    public boolean isCompletedWithoutEvidence() {
        return status == AgentTodoStatus.COMPLETED && evidence.isEmpty();
    }

    /**
     * 将空列表统一转换为不可变空列表。
     *
     * @param values 原始列表，可为空
     * @return 不可变列表
     */
    private static List<String> copyOrEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 将空白字符串归一化为 null，避免校验时重复判断。
     *
     * @param value 原始字符串
     * @return 非空字符串或 null
     */
    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
