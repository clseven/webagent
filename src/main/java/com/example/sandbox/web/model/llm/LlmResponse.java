package com.example.sandbox.web.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 服务层响应
 *
 * <p>对 {@link LlmCompletionResponse} 的业务层抽象，供 Agent 层消费。</p>
 * <p>支持 reasoningContent 字段展示思考链。</p>
 *
 * <h3>多工具调用（并发）</h3>
 * <p>一轮可能返回多个 tool_call。{@code toolCalls} 是完整列表，{@code toolCall} 保留为
 * 列表首个的兼容视图：新代码用 {@link #getToolCalls()} 遍历，旧代码继续用 {@link #getToolCall()}
 * 拿第一个。两者由工厂方法保持同步。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String content;
    private String reasoningContent;

    /** 兼容字段：列表首个 tool_call，供旧调用点使用。 */
    private LlmToolCall toolCall;

    /** 本轮全部 tool_call（并发调度用）。 */
    private List<LlmToolCall> toolCalls;

    private boolean finished;
    private LlmUsage tokenUsage;

    // ==================== 工厂方法 ====================

    public static LlmResponse text(String content) {
        return builder().content(content).finished(true).build();
    }

    public static LlmResponse text(String content, LlmUsage tokenUsage) {
        return builder().content(content).finished(true).tokenUsage(tokenUsage).build();
    }

    public static LlmResponse text(String content, String reasoningContent, LlmUsage tokenUsage) {
        return builder().content(content).reasoningContent(reasoningContent).finished(true).tokenUsage(tokenUsage).build();
    }

    public static LlmResponse toolCall(LlmToolCall toolCall, String thinking) {
        return builder().content(thinking).toolCall(toolCall).toolCalls(List.of(toolCall)).finished(false).build();
    }

    public static LlmResponse toolCall(LlmToolCall toolCall, String thinking, LlmUsage tokenUsage) {
        return builder().content(thinking).toolCall(toolCall).toolCalls(List.of(toolCall)).finished(false).tokenUsage(tokenUsage).build();
    }

    public static LlmResponse toolCall(LlmToolCall toolCall, String thinking, String reasoningContent, LlmUsage tokenUsage) {
        return builder().content(thinking).reasoningContent(reasoningContent).toolCall(toolCall).toolCalls(List.of(toolCall)).finished(false).tokenUsage(tokenUsage).build();
    }

    /**
     * 多 tool_call 工厂：一轮返回多个工具调用时使用。{@code toolCall} 兼容字段取列表首个。
     *
     * @param toolCalls        本轮全部工具调用（非空）
     * @param thinking         模型思考内容
     * @param reasoningContent 思考链
     * @param tokenUsage       token 用量
     * @return 携带完整 tool_call 列表的响应
     */
    public static LlmResponse toolCalls(List<LlmToolCall> toolCalls, String thinking, String reasoningContent, LlmUsage tokenUsage) {
        LlmToolCall first = toolCalls != null && !toolCalls.isEmpty() ? toolCalls.get(0) : null;
        return builder().content(thinking).reasoningContent(reasoningContent)
                .toolCall(first).toolCalls(toolCalls).finished(false).tokenUsage(tokenUsage).build();
    }

    public boolean hasToolCall() {
        return toolCall != null || (toolCalls != null && !toolCalls.isEmpty());
    }

    /**
     * 获取本轮全部工具调用；单调用或旧构造时回退为 {@code toolCall} 的单元素列表。
     *
     * @return 工具调用列表，可能为空列表
     */
    public List<LlmToolCall> getToolCalls() {
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return toolCalls;
        }
        return toolCall != null ? List.of(toolCall) : List.of();
    }
}
