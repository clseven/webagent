package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.llm.AgentStep;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.model.llm.LlmToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据单次运行步骤构建可供后续模型继续使用的协议消息。
 *
 * <p>并发工具调用按相同 stepIndex 合并回同一条 assistant 消息，并在其后按原顺序追加
 * 对应 tool 结果。普通最终回答不保存完整 reasoning。</p>
 */
@Component
public class AgentProtocolBuilder {

    /**
     * 构建一次用户请求的模型协议。
     *
     * @param userMessage 用户原始消息
     * @param steps 本轮工具执行步骤
     * @param finalAnswer 最终回答或暂停提示
     * @return 按时间顺序排列的协议消息
     */
    public List<ChatMessage> build(String userMessage, List<AgentStep> steps, String finalAnswer) {
        List<ChatMessage> messages = new ArrayList<>();
        if (userMessage != null && !userMessage.isBlank()) {
            messages.add(ChatMessage.userMessage(userMessage));
        }

        List<AgentStep> safeSteps = steps != null ? steps : List.of();
        int index = 0;
        while (index < safeSteps.size()) {
            AgentStep first = safeSteps.get(index);
            if (first.toolCall() == null) {
                index++;
                continue;
            }

            int stepIndex = first.stepIndex();
            List<LlmToolCall> toolCalls = new ArrayList<>();
            List<LlmToolResult> toolResults = new ArrayList<>();
            while (index < safeSteps.size() && safeSteps.get(index).stepIndex() == stepIndex) {
                AgentStep step = safeSteps.get(index);
                if (step.toolCall() != null) {
                    toolCalls.add(step.toolCall());
                }
                if (step.toolResult() != null) {
                    toolResults.add(step.toolResult());
                }
                index++;
            }

            messages.add(ChatMessage.assistantToolCallsMessage(
                    first.thinking(), first.reasoning(), toolCalls));
            for (LlmToolResult toolResult : toolResults) {
                messages.add(ChatMessage.toolMessage(toolResult.toolCallId(), toolResult.content()));
            }
        }

        if (finalAnswer != null && !finalAnswer.isBlank()) {
            messages.add(ChatMessage.assistantMessage(finalAnswer));
        }
        return List.copyOf(messages);
    }
}
