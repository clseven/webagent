package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoState;
import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * 最终回答前的门禁 Hook：TodoState 前置硬信号 + 基于证据的模型自检。
 *
 * <h3>职责</h3>
 * <p>在 ReactAgent 准备返回最终答案时分两层把关：</p>
 * <ol>
 *   <li><b>TodoState 前置硬信号（便宜）</b>：若仍有未完成 todo，或 completed todo 缺少 evidence，
 *       直接拦下要求继续或标 blocked。这只是必要条件，闭环不代表证据够。</li>
 *   <li><b>证据自检（TodoState 干净后）</b>：保存首版候选答案并注入自检提示，让模型基于证据
 *       自判“该不该收尾”。harness 不解析模型文本，只靠行为判断——继续调工具表示候选失效，
 *       再次给出最终答案表示通过并原样放行首版候选。</li>
 * </ol>
 */
public class FinalTodoGuardHook implements ReactAgent.StopHook {

    /**
     * 回答前证据自检提示（Stop Hook 注入的 user 消息）。
     *
     * <p>设计要点见方案 §3.3：自检在思考中完成、四类依据分类落地“该查才查”、
     * 靠模型二选一行动而非 harness 解析标记。文本常量化便于后续调优。</p>
     */
    static final String SELF_CHECK_PROMPT = """
            [回答前自检] 在输出最终答案之前，你必须先在思维链（reasoning）中完成以下检查。
            注意：以下检查内容绝对不能出现在最终回答里，只能在思维链中完成。

            1. 拆解：用户实际问了哪几个子问题？逐条列出。
            2. 证据对照：对每个子问题，你是否有依据？
               - 依据类型：工具调用返回 / 用户已提供 / 公共常识 / 纯推断
               - 明确标出：哪些子问题只有”纯推断”、没有前三种依据。
            3. 结论核实：你准备给出的答案里，关键结论是否都已落实（非”纯推断”）？
            4. 冲突：你掌握的多条信息之间有没有矛盾？

            判定与行动（二选一）：
            - 若存在”纯推断”的关键结论、或子问题缺依据、或存在未解决冲突：
              调用工具补查，并说明你要补什么。不要输出最终答案。
            - 若每个子问题都已落实（工具依据 / 用户已提供 / 公共常识，三类任一）且无未解决冲突：
              直接确认收尾。系统会原样放行自检前保存的候选答案，本轮不得重新措辞、扩写或改写答案。
              不要包含上述检查过程，不要标注来源类型。
            """;

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
     * @param messages        当前对话消息
     * @param finalizeAttempt 本会话内模型连续准备收尾的次数（从 1 开始）
     * @return Todo 未闭环时普通继续；Todo 干净时保存候选并进入一次自检
     */
    @Override
    public ReactAgent.StopDecision run(List<ChatMessage> messages, int finalizeAttempt) {
        // 第一层：TodoState 前置硬信号（便宜，未闭环一定拦）
        Optional<AgentTodoState> state = todoService.get(sessionId);
        if (state.isPresent()) {
            List<AgentTodoItem> missingEvidence = state.get().completedWithoutEvidence();
            if (!missingEvidence.isEmpty()) {
                return ReactAgent.StopDecision.continueWith(buildMissingEvidenceReminder(missingEvidence));
            }
            List<AgentTodoItem> openItems = state.get().openItems();
            if (!openItems.isEmpty()) {
                return ReactAgent.StopDecision.continueWith(buildOpenTodoReminder(openItems));
            }
        }

        // 第二层：TodoState 干净 → 保存当前候选并执行一次证据自检
        return ReactAgent.StopDecision.verifyCandidate(SELF_CHECK_PROMPT);
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
