package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.converter.EntityConverter;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ChatMessageEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.llm.AgentContinuation;
import com.example.sandbox.web.model.llm.AgentRunStatus;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.repository.ChatMessageRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证对话持久化能够保存新检查点并兼容旧超限展示事件。
 */
class ConversationContinuationTest {

    private static final String SESSION_ID = "session-1";

    private ConversationSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ConversationServiceImpl conversationService;
    private ConversationSessionEntity session;

    /**
     * 为每个用例准备独立的会话和仓储模拟对象。
     */
    @BeforeEach
    void setUp() {
        sessionRepository = mock(ConversationSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        conversationService = new ConversationServiceImpl(
                sessionRepository, messageRepository, mock(AgentSkillRuntimeService.class));
        session = new ConversationSessionEntity();
        session.setId(SESSION_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    /**
     * 新超限消息必须同时保存展示事件、运行状态和协议级检查点。
     */
    @Test
    void 暂停消息应保存运行状态和协议检查点() {
        List<ChatMessage> checkpointMessages = List.of(
                ChatMessage.userMessage("读取文件"),
                ChatMessage.assistantToolCallMessage(
                        new LlmToolCall("call_1", "read_file", Map.of("path", "/home/gem/a.txt"))),
                ChatMessage.toolMessage("call_1", "文件内容"));
        List<Map<String, Object>> events = List.of(Map.of(
                "type", "toolResult",
                "tool", "read_file",
                "args", Map.of("path", "/home/gem/a.txt"),
                "result", "文件内容"));

        conversationService.addAssistantMessage(
                SESSION_ID,
                ConversationServiceImpl.STREAM_ITERATION_LIMIT_MESSAGE,
                null,
                events,
                AgentRunStatus.PAUSED_MAX_ITERATIONS,
                checkpointMessages);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(messageRepository).save(captor.capture());
        ChatMessageEntity saved = captor.getValue();
        assertThat(saved.getRunStatus()).isEqualTo("PAUSED_MAX_ITERATIONS");
        assertThat(saved.getCheckpointJson()).contains("call_1");
        assertThat(EntityConverter.parseCheckpoint(saved.getCheckpointJson()).toMessages())
                .extracting(ChatMessage::getRole)
                .containsExactly("user", "assistant", "tool");
    }

    /**
     * 新格式暂停消息应优先恢复精确检查点，同时生成供规划器读取的续接说明。
     */
    @Test
    void 新格式暂停消息应恢复精确检查点() {
        List<ChatMessage> checkpointMessages = List.of(
                ChatMessage.userMessage("读取文件"),
                ChatMessage.assistantToolCallMessage(
                        new LlmToolCall("call_1", "read_file", Map.of("path", "/home/gem/a.txt"))),
                ChatMessage.toolMessage("call_1", "文件内容"));
        ChatMessageEntity entity = EntityConverter.toChatMessageEntity(
                session,
                "assistant",
                ConversationServiceImpl.STREAM_ITERATION_LIMIT_MESSAGE,
                null,
                List.of(Map.of(
                        "type", "toolResult",
                        "tool", "read_file",
                        "args", Map.of("path", "/home/gem/a.txt"),
                        "result", "文件内容")));
        entity.setRunStatus(AgentRunStatus.PAUSED_MAX_ITERATIONS.name());
        entity.setCheckpointJson(EntityConverter.serializeCheckpoint(
                com.example.sandbox.web.model.llm.AgentRunCheckpoint.fromMessages(checkpointMessages)));
        when(messageRepository.findFirstBySessionIdOrderByTimestampDesc(SESSION_ID))
                .thenReturn(Optional.of(entity));

        AgentContinuation continuation = conversationService.getLatestContinuation(SESSION_ID);

        assertThat(continuation.exactCheckpoint()).isTrue();
        assertThat(continuation.resumeHistory())
                .extracting(ChatMessage::getRole)
                .containsExactly("user", "assistant", "tool");
        assertThat(continuation.context())
                .contains("read_file")
                .contains("/home/gem/a.txt")
                .contains("文件内容");
    }

    /**
     * 没有 checkpoint_json 的旧超限消息应从 events_json 构造文本续接资料。
     */
    @Test
    void 旧超限消息应从展示事件生成续接上下文() {
        ChatMessageEntity legacy = EntityConverter.toChatMessageEntity(
                session,
                "assistant",
                ConversationServiceImpl.STREAM_ITERATION_LIMIT_MESSAGE,
                null,
                List.of(
                        Map.of("type", "plan", "content", "读取并分析文件"),
                        Map.of(
                                "type", "toolResult",
                                "tool", "read_file",
                                "args", Map.of("path", "/home/gem/a.txt"),
                                "result", "文件内容")));
        when(messageRepository.findFirstBySessionIdOrderByTimestampDesc(SESSION_ID))
                .thenReturn(Optional.of(legacy));

        AgentContinuation continuation = conversationService.getLatestContinuation(SESSION_ID);

        assertThat(continuation.exactCheckpoint()).isFalse();
        assertThat(continuation.suppressLatestHistoryMessage()).isTrue();
        assertThat(continuation.context())
                .contains("读取并分析文件")
                .contains("read_file")
                .contains("/home/gem/a.txt")
                .contains("文件内容");
    }
}
