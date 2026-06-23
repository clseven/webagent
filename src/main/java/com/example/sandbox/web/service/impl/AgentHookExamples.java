package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Agent Hook 使用示例 — 演示如何通过注册 Hook 为 Agent 循环添加扩展行为。
 *
 * <h3>设计原则</h3>
 * <p>这些 Hook 是"挂在循环上"的，不侵入 ReactAgent 的循环代码。
 * 加新功能只需新写一个 Hook 并注册，循环永远不动。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * ReactAgent agent = new ReactAgent(llmService, tools, skillPrompt, plan);
 * agent.registerPreToolUseHook(AgentHookExamples.logHook());
 * agent.registerPostToolUseHook(AgentHookExamples.largeOutputHook());
 * agent.registerStopHook(AgentHookExamples.summaryHook());
 * }</pre>
 *
 * @see ReactAgent.PreToolUseHook
 * @see ReactAgent.PostToolUseHook
 * @see ReactAgent.StopHook
 */
public final class AgentHookExamples {

    private static final Logger log = LoggerFactory.getLogger(AgentHookExamples.class);

    private AgentHookExamples() {
    }

    // ==================== PreToolUse 示例 ====================

    /**
     * 日志 Hook：记录每次工具调用。
     */
    public static ReactAgent.PreToolUseHook logHook() {
        return (toolCall, sessionId, tools) -> {
            log.info("[Hook] 工具调用: {} 参数: {}", toolCall.name(), toolCall.arguments());
            return null; // 永远放行
        };
    }

    /**
     * 权限 Hook：演示用法 — 禁止特定命令。
     *
     * <p>当前为空实现（所有命令放行），可根据需要自定义拒绝列表。</p>
     */
    public static ReactAgent.PreToolUseHook permissionHook() {
        return (toolCall, sessionId, tools) -> {
            if ("bash".equals(toolCall.name()) || "execute_command".equals(toolCall.name())) {
                String command = String.valueOf(toolCall.arguments().getOrDefault("command", ""));
                // 示例：禁止危险命令（按需启用）
                // if (command.contains("rm -rf /")) {
                //     return "权限拒绝：禁止执行 " + command;
                // }
            }
            return null; // 放行
        };
    }

    // ==================== PostToolUse 示例 ====================

    /**
     * 大输出提醒 Hook：工具返回超过 50KB 时记录警告。
     */
    public static ReactAgent.PostToolUseHook largeOutputHook() {
        return (toolCall, result, sessionId) -> {
            if (result != null && result.length() > 50_000) {
                log.warn("[Hook] 大输出: {} 返回 {} 字符", toolCall.name(), result.length());
            }
        };
    }

    // ==================== Stop 示例 ====================

    /**
     * 收尾统计 Hook：循环即将退出时打印工具调用次数。
     */
    public static ReactAgent.StopHook summaryHook() {
        return (messages) -> {
            long toolCount = messages.stream()
                    .filter(m -> "assistant".equals(m.getRole()) && !m.getToolCalls().isEmpty())
                    .count();
            log.info("[Hook] Stop: 本轮共 {} 次工具调用", toolCount);
            return null; // 允许退出
        };
    }
}
