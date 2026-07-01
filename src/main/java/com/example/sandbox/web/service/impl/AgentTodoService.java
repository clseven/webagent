package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.agent.AgentTodoItem;
import com.example.sandbox.web.model.agent.AgentTodoState;
import com.example.sandbox.web.model.agent.AgentTodoStatus;
import com.example.sandbox.web.model.agent.AgentTodoUpdateResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话内 TodoState 服务。
 *
 * <h3>职责</h3>
 * <p>按会话维护轻量运行时任务清单，执行基础状态校验，并生成给模型读取的摘要。
 * 该服务不调度工具、不执行工具，也不跨会话持久化状态。</p>
 */
@Service
public class AgentTodoService {

    /** 会话 ID 到 TodoState 的内存映射。 */
    private final Map<String, AgentTodoState> states = new ConcurrentHashMap<>();

    /** 时钟来源，便于后续测试和状态时间统一。 */
    private final Clock clock;

    /**
     * 创建使用系统时钟的 TodoState 服务。
     */
    public AgentTodoService() {
        this(Clock.systemUTC());
    }

    /**
     * 创建指定时钟的 TodoState 服务。
     *
     * @param clock 更新时间戳来源
     */
    AgentTodoService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 更新指定会话的 TodoState。
     *
     * @param sessionId  会话 ID
     * @param sourcePlan 来源计划摘要，可为空
     * @param items      完整 todo 快照
     * @return 更新结果，包含最新状态、摘要和提醒
     * @throws IllegalArgumentException 参数非法或状态更新违反约束时抛出
     */
    public AgentTodoUpdateResult update(String sessionId, String sourcePlan, List<AgentTodoItem> items) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        List<AgentTodoItem> normalizedItems = normalizeItems(items);
        validateNoSilentDeletion(normalizedSessionId, normalizedItems);
        validateItems(normalizedItems);

        AgentTodoState state = new AgentTodoState(
                normalizedSessionId,
                normalizeBlank(sourcePlan),
                normalizedItems,
                clock.millis());
        states.put(normalizedSessionId, state);

        List<String> warnings = collectWarnings(state);
        return new AgentTodoUpdateResult(state, warnings, buildSummary(state, warnings));
    }

    /**
     * 获取指定会话的当前 TodoState。
     *
     * @param sessionId 会话 ID
     * @return 当前状态；不存在时为空
     */
    public Optional<AgentTodoState> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(sessionId));
    }

    /**
     * 清理指定会话的 TodoState。
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            states.remove(sessionId);
        }
    }

    /**
     * 规范化 todo 列表并过滤空白文本。
     *
     * @param items 原始条目
     * @return 规范化条目
     */
    private List<AgentTodoItem> normalizeItems(List<AgentTodoItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("todos 不能为空");
        }
        List<AgentTodoItem> normalized = new ArrayList<>();
        for (AgentTodoItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException("todos 不能包含空条目");
            }
            normalized.add(new AgentTodoItem(
                    requireText(item.getId(), "id"),
                    requireText(item.getTitle(), "title"),
                    requireStatus(item.getStatus()),
                    cleanList(item.getSuccessSignals()),
                    cleanList(item.getEvidence()),
                    normalizeBlank(item.getBlocker()),
                    normalizeBlank(item.getReason()),
                    cleanList(item.getBatchIds())
            ));
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验条目状态规则。
     *
     * @param items todo 条目
     */
    private void validateItems(List<AgentTodoItem> items) {
        Set<String> ids = new HashSet<>();
        int inProgressCount = 0;
        for (AgentTodoItem item : items) {
            if (!ids.add(item.getId())) {
                throw new IllegalArgumentException("todo id 重复：" + item.getId());
            }
            if (item.getStatus() == AgentTodoStatus.IN_PROGRESS) {
                inProgressCount++;
            }
            if (item.getStatus() == AgentTodoStatus.BLOCKED && item.getBlocker() == null) {
                throw new IllegalArgumentException("blocked todo 必须包含 blocker：" + item.getId());
            }
            if (item.getStatus() == AgentTodoStatus.CANCELLED && item.getReason() == null) {
                throw new IllegalArgumentException("cancelled todo 必须包含 reason：" + item.getId());
            }
        }
        if (inProgressCount > 1) {
            throw new IllegalArgumentException("同一时间最多一个 in_progress todo");
        }
    }

    /**
     * 防止模型通过删除未完成 todo 来绕过最终门禁。
     *
     * @param sessionId 会话 ID
     * @param items     新快照
     */
    private void validateNoSilentDeletion(String sessionId, List<AgentTodoItem> items) {
        AgentTodoState previous = states.get(sessionId);
        if (previous == null) {
            return;
        }
        Set<String> incomingIds = new HashSet<>();
        for (AgentTodoItem item : items) {
            incomingIds.add(item.getId());
        }
        for (AgentTodoItem previousItem : previous.getItems()) {
            boolean canDisappear = previousItem.getStatus() == AgentTodoStatus.COMPLETED
                    || previousItem.getStatus() == AgentTodoStatus.CANCELLED;
            if (!canDisappear && !incomingIds.contains(previousItem.getId())) {
                throw new IllegalArgumentException("不能删除未完成 todo：" + previousItem.getId());
            }
        }
    }

    /**
     * 收集第一版非致命提醒。
     *
     * @param state 最新状态
     * @return 提醒列表
     */
    private List<String> collectWarnings(AgentTodoState state) {
        List<String> warnings = new ArrayList<>();
        for (AgentTodoItem item : state.completedWithoutEvidence()) {
            warnings.add("completed todo 缺少 evidence：" + item.getId());
        }
        return warnings;
    }

    /**
     * 构建给模型读取的紧凑更新摘要。
     *
     * @param state    最新状态
     * @param warnings 非致命提醒
     * @return 摘要文本
     */
    private String buildSummary(AgentTodoState state, List<String> warnings) {
        StringBuilder summary = new StringBuilder();
        summary.append("TodoState 已更新：").append(state.getItems().size()).append(" 项");
        String counts = statusCounts(state);
        if (!counts.isBlank()) {
            summary.append("，").append(counts);
        }
        summary.append("。");

        state.getItems().stream()
                .filter(item -> item.getStatus() == AgentTodoStatus.IN_PROGRESS)
                .findFirst()
                .ifPresent(item -> summary.append("\n当前进行中：").append(item.getTitle()).append("。"));
        for (String warning : warnings) {
            summary.append("\n提醒：").append(warning);
        }
        return summary.toString();
    }

    /**
     * 生成状态数量摘要。
     *
     * @param state 最新状态
     * @return 形如 1 completed，1 in_progress 的文本
     */
    private String statusCounts(AgentTodoState state) {
        Map<AgentTodoStatus, Long> counts = new EnumMap<>(AgentTodoStatus.class);
        for (AgentTodoStatus status : AgentTodoStatus.values()) {
            long count = state.count(status);
            if (count > 0) {
                counts.put(status, count);
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<AgentTodoStatus, Long> entry : counts.entrySet()) {
            parts.add(entry.getValue() + " " + entry.getKey().getWireValue());
        }
        return String.join("，", parts);
    }

    /**
     * 去掉空白字符串并复制为不可变列表。
     *
     * @param values 原始字符串列表
     * @return 清理后的列表
     */
    private List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String normalized = normalizeBlank(value);
            if (normalized != null) {
                cleaned.add(normalized);
            }
        }
        return List.copyOf(cleaned);
    }

    /**
     * 校验必填文本。
     *
     * @param value 原始文本
     * @param field 字段名
     * @return 去除首尾空白后的文本
     */
    private String requireText(String value, String field) {
        String normalized = normalizeBlank(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    /**
     * 校验状态对象。
     *
     * @param status 状态
     * @return 非空状态
     */
    private AgentTodoStatus requireStatus(AgentTodoStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        return status;
    }

    /**
     * 将空白字符串归一化为 null。
     *
     * @param value 原始文本
     * @return 非空文本或 null
     */
    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
