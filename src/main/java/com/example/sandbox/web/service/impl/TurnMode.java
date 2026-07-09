package com.example.sandbox.web.service.impl;

/**
 * 本轮用户消息的任务类型分类。
 *
 * <p>由 {@link TurnModeClassifier} 产出，供 {@link TurnPolicyResolver} 映射为策略。</p>
 */
public enum TurnMode {

    /** 纯社交：打招呼、感谢、告别，无需工具或规划。 */
    SOCIAL,

    /** 明确任务：需要规划、工具、工作区等全能力注入。 */
    TASK,

    /** 无法判断，保守按 TASK 全能力处理。 */
    AMBIGUOUS
}
