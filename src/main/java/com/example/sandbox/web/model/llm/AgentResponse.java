package com.example.sandbox.web.model.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 完整执行结果
 *
 * <p>替代 {@code ReactResult}，提供更丰富的结构化信息：</p>
 * <ul>
 *   <li>完整的迭代链（steps）— 可用于前端展示"思考过程"</li>
 *   <li>累积的 token 用量</li>
 *   <li>最终回答内容和思考链</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private String finalAnswer;
    private String finalReasoning;
    private List<AgentStep> steps;
    private LlmUsage totalUsage;
    private int iterations;

    public boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }
}
