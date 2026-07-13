package com.example.sandbox.web.model.llm;

import com.example.sandbox.web.model.entity.ChatMessage;
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
public class AgentResponse {

    private String finalAnswer;
    private String finalReasoning;
    private List<AgentStep> steps;
    private LlmUsage totalUsage;
    private int iterations;

    /** 本次运行的结束状态，正常完成时为 {@link AgentRunStatus#COMPLETED}。 */
    private AgentRunStatus runStatus = AgentRunStatus.COMPLETED;

    /** 达到执行上限时保存的精确协议消息，正常完成时为空。 */
    private List<ChatMessage> checkpointMessages = List.of();

    /**
     * 创建正常完成的 Agent 响应。
     *
     * @param finalAnswer 最终回答
     * @param finalReasoning 最终思考内容，可为空
     * @param steps 执行步骤
     * @param totalUsage 累计 token 用量
     * @param iterations 执行轮数
     */
    public AgentResponse(String finalAnswer, String finalReasoning, List<AgentStep> steps,
                         LlmUsage totalUsage, int iterations) {
        this(finalAnswer, finalReasoning, steps, totalUsage, iterations,
                AgentRunStatus.COMPLETED, List.of());
    }

    /**
     * 创建带运行状态和协议检查点的 Agent 响应。
     *
     * @param finalAnswer 最终回答或暂停提示
     * @param finalReasoning 最终思考内容，可为空
     * @param steps 执行步骤
     * @param totalUsage 累计 token 用量
     * @param iterations 执行轮数
     * @param runStatus 本次运行状态
     * @param checkpointMessages 可供下一轮恢复的协议消息
     */
    public AgentResponse(String finalAnswer, String finalReasoning, List<AgentStep> steps,
                         LlmUsage totalUsage, int iterations, AgentRunStatus runStatus,
                         List<ChatMessage> checkpointMessages) {
        this.finalAnswer = finalAnswer;
        this.finalReasoning = finalReasoning;
        this.steps = steps;
        this.totalUsage = totalUsage;
        this.iterations = iterations;
        this.runStatus = runStatus != null ? runStatus : AgentRunStatus.COMPLETED;
        this.checkpointMessages = checkpointMessages != null
                ? List.copyOf(checkpointMessages)
                : List.of();
    }

    public boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }
}
