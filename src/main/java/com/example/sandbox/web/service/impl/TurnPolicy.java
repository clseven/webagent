package com.example.sandbox.web.service.impl;

/**
 * 单轮对话的策略开关，控制本轮是否注入规划、工具、技能、工作区、知识库和 StopHook。
 *
 * <p>由 {@link TurnPolicyResolver} 根据 {@link TurnMode} 产出，在
 * {@link AgentTurnContextService#prepare} 中消费。</p>
 *
 * @param mode                 本轮分类
 * @param shouldPlan           是否运行 PlanAgent
 * @param shouldGiveTools      是否注入工具定义
 * @param shouldInjectSkill    是否注入技能提示（已启用技能元数据）
 * @param shouldInjectWorkspace 是否注入工作区目录记忆
 * @param shouldInjectKB       是否运行知识库增强
 * @param shouldEnableStopHook 是否启用 StopHook
 */
public record TurnPolicy(
        TurnMode mode,
        boolean shouldPlan,
        boolean shouldGiveTools,
        boolean shouldInjectSkill,
        boolean shouldInjectWorkspace,
        boolean shouldInjectKB,
        boolean shouldEnableStopHook) {

    /** 全能力注入（TASK / AMBIGUOUS 默认策略）。 */
    public static final TurnPolicy FULL = new TurnPolicy(
            TurnMode.TASK, true, true, true, true, true, true);

    /** 最小化注入（SOCIAL 默认策略）。 */
    public static final TurnPolicy LITE = new TurnPolicy(
            TurnMode.SOCIAL, false, false, false, false, false, false);

    /**
     * 根据分类模式产出默认策略。
     *
     * @param mode 本轮分类
     * @return 对应策略
     */
    public static TurnPolicy forMode(TurnMode mode) {
        return switch (mode) {
            case SOCIAL -> LITE;
            case TASK, AMBIGUOUS -> FULL;
        };
    }
}
