package com.example.sandbox.web.model.llm;

/**
 * Agent 单次迭代记录
 *
 * <p>记录 ReAct 循环中一次完整的 Thought → Action → Observation 链路。</p>
 *
 * @param stepIndex   步骤序号（从 1 开始）
 * @param thinking    LLM 的思考内容（Thought）
 * @param reasoning   LLM 的思考链（reasoning_content，部分模型支持）
 * @param toolCall    工具调用（Action）
 * @param toolResult  工具执行结果（Observation）
 * @param tokenUsage  本次 LLM 调用的 token 用量
 */
public record AgentStep(
        int stepIndex,
        String thinking,
        String reasoning,
        LlmToolCall toolCall,
        LlmToolResult toolResult,
        LlmUsage tokenUsage
) {
}
