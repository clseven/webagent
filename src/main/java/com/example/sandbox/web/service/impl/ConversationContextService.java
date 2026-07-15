package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.AgentRunEntity;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationContextEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.llm.AgentRunCheckpoint;
import com.example.sandbox.web.model.llm.ConversationContextView;
import com.example.sandbox.web.model.llm.ConversationSummary;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.repository.AgentRunRepository;
import com.example.sandbox.web.repository.ConversationContextRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.LlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级持久上下文服务，负责从运行账本维护长期摘要和最近协议快照。
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);
    private static final int CATCH_UP_PAGE_SIZE = 20;
    private static final String OLD_TOOL_RESULT_MARKER =
            "[Earlier tool result compacted. Re-run the tool if the full result is needed.]";
    private static final String LARGE_TOOL_RESULT_MARKER =
            "[Large tool result persisted in agent_run. Re-run with a narrower range if needed.]";

    /** 会话上下文 Repository。 */
    private final ConversationContextRepository contextRepository;
    /** Agent 运行 Repository。 */
    private final AgentRunRepository agentRunRepository;
    /** 会话 Repository。 */
    private final ConversationSessionRepository sessionRepository;
    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;
    /** 摘要模型服务。 */
    private final LlmService llmService;
    /** token 估算器。 */
    private final ConversationContextTokenEstimator tokenEstimator;
    /** 上下文配置。 */
    private final AgentConfigProperties.Context properties;
    /** 单实例内的会话更新锁。 */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /** 创建持久上下文服务。 */
    public ConversationContextService(ConversationContextRepository contextRepository,
                                      AgentRunRepository agentRunRepository,
                                      ConversationSessionRepository sessionRepository,
                                      ObjectMapper objectMapper,
                                      @Qualifier("executorLlm") LlmService llmService,
                                      ConversationContextTokenEstimator tokenEstimator,
                                      AgentConfigProperties configProperties) {
        this.contextRepository = contextRepository;
        this.agentRunRepository = agentRunRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.llmService = llmService;
        this.tokenEstimator = tokenEstimator;
        this.properties = configProperties.getContext();
    }

    /** 将新运行追加到上下文快照。 */
    public void appendRun(AgentRunEntity run) {
        if (!properties.isEnabled() || run == null || run.getId() == null) {
            return;
        }
        String sessionId = run.getSession().getId();
        synchronized (lockFor(sessionId)) {
            ConversationContextEntity context = loadOrCreate(sessionId);
            appendRunLocked(context, run);
            contextRepository.save(context);
        }
    }

    /** 加载当前轮长期摘要和最近完整协议。 */
    public ConversationContextView load(String sessionId) {
        if (!properties.isEnabled()) {
            return ConversationContextView.empty();
        }
        synchronized (lockFor(sessionId)) {
            ConversationContextEntity context = loadOrCreate(sessionId);
            catchUpLocked(context);
            List<ChatMessage> messages = applyLogicalCompaction(parseProtocol(context.getRecentProtocolJson()));
            String summary = parseSummary(context.getSummaryJson());
            int tokens = tokenEstimator.estimateMessages(messages);
            if (tokens > properties.getSummarizeThresholdTokens()) {
                int splitAt = chooseSummarySplit(messages, tokens);
                if (splitAt > 0) {
                    String updated = summarize(summary, new ArrayList<>(messages.subList(0, splitAt)));
                    if (updated != null) {
                        summary = updated;
                        messages = new ArrayList<>(messages.subList(splitAt, messages.size()));
                    }
                }
            }
            saveSnapshot(context, summary, messages);
            return new ConversationContextView(summary, List.copyOf(messages));
        }
    }

    /**
     * 对运行中的协议执行零 API 调用逻辑压缩。
     *
     * @param messages 当前模型协议
     * @return 完成 L3 大结果预览和 L2 旧结果占位后的消息
     */
    public List<ChatMessage> compactProtocolMessages(List<ChatMessage> messages) {
        return List.copyOf(applyLogicalCompaction(messages != null ? messages : List.of()));
    }

    /** 获取触发 LLM 摘要的 token 阈值。 */
    public int getSummarizeThresholdTokens() {
        return properties.getSummarizeThresholdTokens();
    }

    /** 获取 LLM 摘要后的目标 token。 */
    public int getCompactTargetTokens() {
        return properties.getCompactTargetTokens();
    }

    /** 估算指定协议消息的 token。 */
    public int estimateTokens(List<ChatMessage> messages) {
        return tokenEstimator.estimateMessages(messages);
    }

    /** 删除指定会话上下文。 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            contextRepository.deleteById(sessionId);
            sessionLocks.remove(sessionId);
        }
    }

    /** 将摘要格式化为系统上下文片段。 */
    public String formatSummaryContext(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        return "## 会话长期记忆" + System.lineSeparator() + System.lineSeparator() + summary.trim();
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private ConversationContextEntity loadOrCreate(String sessionId) {
        return contextRepository.findById(sessionId).orElseGet(() -> {
            ConversationSessionEntity session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new SessionNotFoundException(sessionId));
            ConversationContextEntity context = new ConversationContextEntity();
            context.setSessionId(sessionId);
            context.setSummaryJson(serializeSummary(null));
            context.setRecentProtocolJson(serializeProtocol(List.of()));
            return context;
        });
    }

    private void catchUpLocked(ConversationContextEntity context) {
        while (true) {
            Long lastRunId = context.getLastAppliedRunId();
            List<AgentRunEntity> runs = lastRunId == null
                    ? agentRunRepository.findBySession_IdOrderByIdAsc(
                            context.getSessionId(), PageRequest.of(0, CATCH_UP_PAGE_SIZE))
                    : agentRunRepository.findBySession_IdAndIdGreaterThanOrderByIdAsc(
                            context.getSessionId(), lastRunId, PageRequest.of(0, CATCH_UP_PAGE_SIZE));
            if (runs.isEmpty()) {
                return;
            }
            for (AgentRunEntity run : runs) {
                appendRunLocked(context, run);
            }
            contextRepository.save(context);
            if (runs.size() < CATCH_UP_PAGE_SIZE) {
                return;
            }
        }
    }

    private void appendRunLocked(ConversationContextEntity context, AgentRunEntity run) {
        if (context.getLastAppliedRunId() != null && run.getId() <= context.getLastAppliedRunId()) {
            return;
        }
        List<ChatMessage> messages = new ArrayList<>(parseProtocol(context.getRecentProtocolJson()));
        messages.addAll(parseProtocol(run.getProtocolJson()));
        messages = applyLogicalCompaction(messages);
        context.setRecentProtocolJson(serializeProtocol(messages));
        context.setRecentProtocolTokens(tokenEstimator.estimateMessages(messages));
        context.setLastAppliedRunId(run.getId());
        context.touch();
    }

    private void saveSnapshot(ConversationContextEntity context, String summary, List<ChatMessage> messages) {
        context.setSummaryJson(serializeSummary(summary));
        context.setRecentProtocolJson(serializeProtocol(messages));
        context.setRecentProtocolTokens(tokenEstimator.estimateMessages(messages));
        context.touch();
        contextRepository.save(context);
    }

    private List<ChatMessage> applyLogicalCompaction(List<ChatMessage> source) {
        List<ChatMessage> messages = new ArrayList<>(source.size());
        for (ChatMessage message : source) {
            messages.add(compactLargeToolResult(message));
        }

        int recentToolTokens = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (!"tool".equals(message.getRole())) {
                continue;
            }
            recentToolTokens += tokenEstimator.estimateMessages(List.of(message));
            if (recentToolTokens > properties.getRecentToolResultTokens()
                    && message.getContent() != null && message.getContent().length() > 120) {
                messages.set(index, replaceContent(message, OLD_TOOL_RESULT_MARKER));
            }
        }
        return messages;
    }

    private ChatMessage compactLargeToolResult(ChatMessage message) {
        if (!"tool".equals(message.getRole()) || message.getContent() == null
                || message.getContent().length() <= properties.getMaxToolResultChars()) {
            return message;
        }
        int preview = Math.max(200, properties.getToolResultPreviewChars());
        String content = message.getContent();
        int headEnd = Math.min(preview, content.length());
        int tailStart = Math.max(headEnd, content.length() - preview);
        String compacted = content.substring(0, headEnd)
                + System.lineSeparator() + LARGE_TOOL_RESULT_MARKER + System.lineSeparator()
                + content.substring(tailStart);
        return replaceContent(message, compacted);
    }

    private ChatMessage replaceContent(ChatMessage message, String content) {
        return ChatMessage.restoreProtocol(
                message.getRole(), content, message.getReasoning(), message.getTimestamp(),
                message.getToolCallId(), message.getToolCalls());
    }

    private int chooseSummarySplit(List<ChatMessage> messages, int totalTokens) {
        int tokensToRemove = Math.max(1, totalTokens - properties.getCompactTargetTokens());
        int removedTokens = 0;
        int candidate = 0;
        for (int index = 0; index < messages.size() - 1; index++) {
            removedTokens += tokenEstimator.estimateMessages(List.of(messages.get(index)));
            candidate = index + 1;
            if (removedTokens >= tokensToRemove) {
                break;
            }
        }
        return alignSplitAtToToolCallBoundary(messages, candidate);
    }

    private int alignSplitAtToToolCallBoundary(List<ChatMessage> messages, int candidateSplitAt) {
        if (candidateSplitAt <= 0 || candidateSplitAt >= messages.size()) {
            return candidateSplitAt;
        }
        int openGroupStart = -1;
        List<String> pendingIds = new ArrayList<>();
        for (int index = 0; index < candidateSplitAt; index++) {
            ChatMessage message = messages.get(index);
            if ("assistant".equals(message.getRole()) && !message.getToolCalls().isEmpty()) {
                if (pendingIds.isEmpty()) {
                    openGroupStart = index;
                }
                for (LlmToolCall toolCall : message.getToolCalls()) {
                    pendingIds.add(toolCall.id());
                }
            } else if ("tool".equals(message.getRole()) && openGroupStart >= 0) {
                pendingIds.remove(message.getToolCallId());
                if (pendingIds.isEmpty()) {
                    openGroupStart = -1;
                }
            }
        }
        return openGroupStart >= 0 ? openGroupStart : candidateSplitAt;
    }

    private String summarize(String existingSummary, List<ChatMessage> oldMessages) {
        String prompt = """
                请用中文更新会话长期记忆，只输出摘要正文，不调用工具，不输出分析过程。
                保留目标、约束、结论、证据、文件变化、命令与验证结果、阻塞和待办。
                不要保存普通最终回答的完整思考链，不确定信息标记为“未确认”。

                已有摘要：
                %s

                旧协议：
                %s
                """.formatted(
                existingSummary == null || existingSummary.isBlank() ? "（首次摘要）" : existingSummary,
                formatMessagesForSummary(oldMessages));
        try {
            String summary = llmService.chat(List.of(ChatMessage.userMessage(prompt)));
            return summary != null && !summary.isBlank() ? summary.trim() : null;
        } catch (Exception exception) {
            log.warn("会话上下文 L4 摘要失败，保留原始协议: {}", exception.getMessage(), exception);
            return null;
        }
    }

    private String formatMessagesForSummary(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (ChatMessage message : messages) {
            builder.append('[').append(index++).append("] role=").append(message.getRole()).append('\n');
            for (LlmToolCall toolCall : message.getToolCalls()) {
                builder.append("tool_call id=").append(toolCall.id())
                        .append(" name=").append(toolCall.name())
                        .append(" arguments=").append(toolCall.arguments()).append('\n');
            }
            if (message.getToolCallId() != null) {
                builder.append("tool_call_id=").append(message.getToolCallId()).append('\n');
            }
            if (message.getContent() != null && !message.getContent().isBlank()) {
                builder.append("content=").append(message.getContent()).append('\n');
            }
        }
        return builder.toString();
    }

    private String serializeProtocol(List<ChatMessage> messages) {
        try {
            return objectMapper.writeValueAsString(AgentRunCheckpoint.fromMessages(messages));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize conversation protocol", exception);
        }
    }

    private List<ChatMessage> parseProtocol(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json, AgentRunCheckpoint.class).toMessages());
        } catch (JsonProcessingException exception) {
            log.warn("会话协议 JSON 损坏，按空协议降级: {}", exception.getMessage());
            return List.of();
        }
    }

    private String serializeSummary(String summary) {
        try {
            return objectMapper.writeValueAsString(ConversationSummary.of(summary));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize conversation summary", exception);
        }
    }

    private String parseSummary(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            ConversationSummary summary = objectMapper.readValue(json, ConversationSummary.class);
            return summary.version() == ConversationSummary.CURRENT_VERSION ? summary.content() : "";
        } catch (JsonProcessingException exception) {
            log.warn("会话摘要 JSON 损坏，按空摘要降级: {}", exception.getMessage());
            return "";
        }
    }
}
