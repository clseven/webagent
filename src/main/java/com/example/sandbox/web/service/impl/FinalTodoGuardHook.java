package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoState;
import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * 最终回答前的 TodoState 门禁 Hook。
 *
 * <h3>职责</h3>
 * <p>在 ReactAgent 准备返回最终答案时检查当前会话 TodoState。若仍有未完成 todo，
 * 或 completed todo 缺少 evidence，则返回一条用户消息强制执行器继续循环。</p>
 */
public class FinalTodoGuardHook implements ReactAgent.StopHook {

    /** TodoState 服务。 */
    private final AgentTodoService todoService;

    /** 当前会话 ID。 */
    private final String sessionId;

    /**
     * 创建最终门禁 Hook。
     *
     * @param todoService TodoState 服务
     * @param sessionId   当前会话 ID
     */
    public FinalTodoGuardHook(AgentTodoService todoService, String sessionId) {
        this.todoService = todoService;
        this.sessionId = sessionId;
    }

    /**
     * 检查最终回答是否可以放行。
     *
     * @param messages 当前对话消息；第一版不读取消息内容，只按会话 TodoState 判断
     * @return null 表示允许最终回答；非 null 表示注入提醒并继续循环
     */
    @Override
    public String run(List<ChatMessage> messages) {
        Optional<AgentTodoState> state = todoService.get(sessionId);
        if (state.isEmpty()) {
            return null;
        }
        List<AgentTodoItem> missingEvidence = state.get().completedWithoutEvidence();
        if (!missingEvidence.isEmpty()) {
            return buildMissingEvidenceReminder(missingEvidence);
        }
        List<AgentTodoItem> openItems = state.get().openItems();
        if (!openItems.isEmpty()) {
            return buildOpenTodoReminder(openItems);
        }
        return null;
    }

    /**
     * 构建缺少证据的提醒消息。
     *
     * @param items 缺少 evidence 的 completed todo
     * @return 注入给执行器的提醒
     */
    private String buildMissingEvidenceReminder(List<AgentTodoItem> items) {
        return """
                TodoState 最终门禁：存在 completed todo 缺少 evidence，不能直接最终回答。
                请调用 todo_write 补充来自工具 observation、用户确认或可验证输出的 evidence；
                如果其实没有完成，请把状态改回 in_progress 或 blocked。

                需要补证据：
                %s
                """.formatted(formatItems(items));
    }

    /**
     * 构建未完成 todo 的提醒消息。
     *
     * @param items pending 或 in_progress todo
     * @return 注入给执行器的提醒
     */
    private String buildOpenTodoReminder(List<AgentTodoItem> items) {
        return """
                TodoState 最终门禁：仍有关键 todo 未闭环，不能直接最终回答。
                请继续执行当前任务；如果确实无法推进，请调用 todo_write 将对应 todo 标记为 blocked，
                并填写 blocker 说明需要用户、权限、环境还是外部信息。

                未完成 todo：
                %s
                """.formatted(formatItems(items));
    }

    /**
     * 格式化 todo 列表。
     *
     * @param items todo 条目
     * @return 多行列表文本
     */
    private String formatItems(List<AgentTodoItem> items) {
        StringBuilder builder = new StringBuilder();
        for (AgentTodoItem item : items) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("- ")
                    .append(item.getId())
                    .append("：")
                    .append(item.getTitle())
                    .append("（")
                    .append(item.getStatus().getWireValue())
                    .append("）");
        }
        return builder.toString();
    }
}
