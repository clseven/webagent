package com.example.sandbox.web.model.sse;

import com.example.sandbox.web.model.llm.LlmUsage;

import java.util.Map;

/**
 * SSE 事件模型 — 流式输出的事件载体
 *
 * <h3>用途</h3>
 * <p>封装 SSE 流式输出的各种事件类型，前端根据 type 字段区分处理：</p>
 * <ul>
 *   <li>token — LLM 生成的单个 token（打字效果）</li>
 *   <li>reasoning_token — 思考链 token（推理模型的思考过程）</li>
 *   <li>thinking_start/end — 一轮思考的开始/结束</li>
 *   <li>tool_call — 工具调用开始</li>
 *   <li>tool_executing — 工具执行中</li>
 *   <li>observation — 工具执行结果</li>
 *   <li>plan — 规划结果</li>
 *   <li>answer — 最终答案</li>
 *   <li>done — 执行完成</li>
 *   <li>error — 错误</li>
 *   <li>interrupted — 用户中断</li>
 * </ul>
 *
 * @author example
 * @date 2026/06/04
 */
public record SseEvent(
    String type,
    Map<String, Object> data
) {
    // ==================== Token 级流式事件 ====================

    /**
     * LLM 输出的普通 token（实时打字效果）
     */
    public static SseEvent token(String content) {
        return new SseEvent("token", Map.of("content", content));
    }

    /**
     * 思考链 token（DeepSeek R1 等推理模型的思考过程）
     */
    public static SseEvent reasoningToken(String content) {
        return new SseEvent("reasoning_token", Map.of("content", content));
    }

    // ==================== 思考过程事件 ====================

    /**
     * 一轮思考开始
     *
     * @param stepIndex 当前是第几轮（从 1 开始）
     */
    public static SseEvent thinkingStart(int stepIndex) {
        return new SseEvent("thinking_start", Map.of("stepIndex", stepIndex));
    }

    /**
     * 一轮思考结束
     */
    public static SseEvent thinkingEnd() {
        return new SseEvent("thinking_end", Map.of());
    }

    // ==================== 工具调用事件 ====================

    /**
     * 工具调用开始
     *
     * @param tool  工具名称
     * @param args  工具参数
     * @param stepIndex 当前轮次
     */
    public static SseEvent toolCall(String tool, Map<String, Object> args, int stepIndex) {
        return new SseEvent("tool_call", Map.of(
            "tool", tool,
            "args", args,
            "stepIndex", stepIndex
        ));
    }

    /**
     * 工具执行中（可选，前端显示转圈动画和耗时）
     *
     * @param tool    工具名称
     * @param elapsed 已执行毫秒数
     */
    public static SseEvent toolExecuting(String tool, long elapsed) {
        return new SseEvent("tool_executing", Map.of(
            "tool", tool,
            "elapsed", elapsed
        ));
    }

    /**
     * 工具执行结果
     *
     * @param tool     工具名称
     * @param result   执行结果
     * @param duration 执行耗时（毫秒）
     */
    public static SseEvent observation(String tool, String result, long duration) {
        return new SseEvent("observation", Map.of(
            "tool", tool,
            "result", result,
            "duration", duration
        ));
    }

    // ==================== 规划事件 ====================

    /**
     * 规划结果
     *
     * @param content 规划内容
     */
    public static SseEvent plan(String content) {
        return new SseEvent("plan", Map.of("content", content));
    }

    // ==================== 最终答案事件 ====================

    /**
     * 最终答案（非折叠，直接展示）
     *
     * @param content  答案内容
     * @param reasoning 思考链内容（可为 null）
     */
    public static SseEvent answer(String content, String reasoning) {
        Map<String, Object> data = reasoning != null
            ? Map.of("content", content, "reasoning", reasoning)
            : Map.of("content", content);
        return new SseEvent("answer", data);
    }

    // ==================== 状态事件 ====================

    /**
     * 执行完成
     *
     * @param iterations 总迭代次数
     * @param usage      Token 用量（可为 null）
     */
    public static SseEvent done(int iterations, LlmUsage usage) {
        Map<String, Object> data = usage != null
            ? Map.of("iterations", iterations, "tokenUsage", Map.of(
                "promptTokens", usage.promptTokens(),
                "completionTokens", usage.completionTokens(),
                "totalTokens", usage.totalTokens(),
                "cacheHitTokens", usage.cacheHitTokens()
            ))
            : Map.of("iterations", iterations);
        return new SseEvent("done", data);
    }

    /**
     * 错误事件
     *
     * @param message 错误信息
     */
    public static SseEvent error(String message) {
        return new SseEvent("error", Map.of("message", message));
    }

    /**
     * 用户中断事件
     *
     * @param reason 中断原因
     */
    public static SseEvent interrupted(String reason) {
        return new SseEvent("interrupted", Map.of("reason", reason));
    }

    /**
     * 心跳事件（保持 SSE 连接活跃）
     */
    public static SseEvent heartbeat() {
        return new SseEvent("heartbeat", Map.of());
    }

    /**
     * 状态消息（如等待后台任务等）
     *
     * @param message 状态描述
     */
    public static SseEvent status(String message) {
        return new SseEvent("status", Map.of("message", message));
    }
}
