package com.example.sandbox.web.model.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Agent 执行过程映射为前端历史展示事件。
 *
 * <p>该类负责把后端已有的 plan 和 AgentStep 转成 Chat.js 已经支持的 msg.events 结构。
 * 这些事件只用于刷新后的过程展示恢复，不参与后续模型上下文。</p>
 */
public final class AgentEventMapper {

    private AgentEventMapper() {
    }

    /**
     * 将执行计划和步骤列表转换为前端可展示的事件列表。
     *
     * @param plan  PlanAgent 生成的执行计划，可为空
     * @param steps ReAct 执行步骤，可为空
     * @return 与前端 msg.events 兼容的事件列表
     */
    public static List<Map<String, Object>> fromPlanAndSteps(String plan, List<AgentStep> steps) {
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> planEvent = planEvent(plan);
        if (planEvent != null) {
            events.add(planEvent);
        }
        if (steps == null || steps.isEmpty()) {
            return events;
        }
        for (AgentStep step : steps) {
            events.addAll(fromStep(step));
        }
        return events;
    }

    /**
     * 将单个 Agent 步骤转换为展示事件。
     *
     * @param step Agent 单轮执行步骤，可为 null
     * @return thinking、reasoning、toolResult 等事件；没有可展示内容时为空列表
     */
    public static List<Map<String, Object>> fromStep(AgentStep step) {
        if (step == null) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> thinkingEvent = thinkingEvent(step.stepIndex(), step.thinking());
        if (thinkingEvent != null) {
            events.add(thinkingEvent);
        }
        Map<String, Object> reasoningEvent = reasoningEvent(step.stepIndex(), step.reasoning());
        if (reasoningEvent != null) {
            events.add(reasoningEvent);
        }
        Map<String, Object> toolResultEvent = toolResultEvent(step.toolCall(), step.toolResult());
        if (toolResultEvent != null) {
            events.add(toolResultEvent);
        }
        return events;
    }

    /**
     * 创建计划事件。
     *
     * @param plan 执行计划文本，可为空
     * @return plan 事件；空计划返回 null
     */
    public static Map<String, Object> planEvent(String plan) {
        if (isBlank(plan)) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "plan");
        event.put("content", plan);
        return event;
    }

    /**
     * 创建思考内容事件。
     *
     * @param stepIndex 步骤序号
     * @param thinking  模型在工具调用前输出的思考文本，可为空
     * @return thinking 事件；空内容返回 null
     */
    public static Map<String, Object> thinkingEvent(int stepIndex, String thinking) {
        if (isBlank(thinking)) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "thinking");
        event.put("content", thinking);
        event.put("stepIndex", stepIndex);
        return event;
    }

    /**
     * 创建思考链事件。
     *
     * @param stepIndex 步骤序号
     * @param reasoning 模型 reasoning_content 内容，可为空
     * @return reasoning 事件；空内容返回 null
     */
    public static Map<String, Object> reasoningEvent(int stepIndex, String reasoning) {
        if (isBlank(reasoning)) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "reasoning");
        event.put("content", reasoning);
        event.put("stepIndex", stepIndex);
        return event;
    }

    /**
     * 创建工具结果事件。
     *
     * @param toolCall   工具调用信息，可为空
     * @param toolResult 工具执行结果，可为空
     * @return toolResult 事件；没有工具结果时返回 null
     */
    public static Map<String, Object> toolResultEvent(LlmToolCall toolCall, LlmToolResult toolResult) {
        if (toolResult == null) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "toolResult");
        event.put("tool", !isBlank(toolResult.toolName()) ? toolResult.toolName() :
                toolCall != null ? toolCall.name() : null);
        event.put("args", toolCall != null && toolCall.arguments() != null ? toolCall.arguments() : Map.of());
        event.put("result", toolResult.content());
        event.put("duration", toolResult.durationMs());
        if (!isBlank(toolResult.displayReason())) {
            event.put("displayReason", toolResult.displayReason());
        }
        return event;
    }

    /**
     * 判断文本是否为空白。
     *
     * @param value 待检查文本
     * @return true 表示 null、空串或纯空白
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
