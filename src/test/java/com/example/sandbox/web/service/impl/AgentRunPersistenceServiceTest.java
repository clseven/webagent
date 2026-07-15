package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.AgentRunEntity;
import com.example.sandbox.web.model.entity.ConversationContextEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.llm.AgentRunCheckpoint;
import com.example.sandbox.web.model.llm.AgentRunStatus;
import com.example.sandbox.web.model.llm.AgentStep;
import com.example.sandbox.web.model.llm.ConversationContextView;
import com.example.sandbox.web.model.llm.ConversationSummary;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.model.llm.LlmToolResult;
import com.example.sandbox.web.model.llm.LlmUsage;
import com.example.sandbox.web.repository.AgentRunRepository;
import com.example.sandbox.web.repository.ConversationContextRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * 验证 Agent 运行轨迹按一次用户请求写入一条数据库记录。
 */
class AgentRunPersistenceServiceTest {

    /**
     * 完成运行时应保存规划、工具步骤、最终回答、状态和 token 用量。
     */
    @Test
    void 应保存单条完整运行记录() throws Exception {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ConversationContextService contextService = mock(ConversationContextService.class);
        AgentRunPersistenceService service = new AgentRunPersistenceService(
                runRepository, sessionRepository, objectMapper,
                new AgentProtocolBuilder(),
                new ConversationContextTokenEstimator(new AgentConfigProperties()),
                contextService);
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setId("session-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        AgentStep step = new AgentStep(
                1,
                "准备读取配置",
                "需要先获得配置证据",
                new LlmToolCall("call-1", "read_file", Map.of("path", "config.yml")),
                LlmToolResult.success("call-1", "read_file", "配置内容", 12),
                new LlmUsage(10, 4, 14, 2));

        service.save(
                "session-1",
                "请读取配置",
                "读取配置后回答",
                List.of(step),
                "处理完成",
                AgentRunStatus.COMPLETED,
                new LlmUsage(30, 8, 38, 5),
                2);

        ArgumentCaptor<AgentRunEntity> entityCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runRepository).save(entityCaptor.capture());
        AgentRunEntity saved = entityCaptor.getValue();
        JsonNode trace = objectMapper.readTree(saved.getTraceJson());
        List<ChatMessage> protocol = objectMapper.readValue(
                saved.getProtocolJson(), AgentRunCheckpoint.class).toMessages();

        assertThat(saved.getSession()).isSameAs(session);
        assertThat(saved.getStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getIterations()).isEqualTo(2);
        assertThat(saved.getPromptTokens()).isEqualTo(30);
        assertThat(saved.getCompletionTokens()).isEqualTo(8);
        assertThat(saved.getCacheHitTokens()).isEqualTo(5);
        assertThat(saved.getTotalTokens()).isEqualTo(38);
        assertThat(saved.getProtocolTokens()).isPositive();
        assertThat(trace.path("version").asInt()).isEqualTo(1);
        assertThat(trace.path("plan").asText()).isEqualTo("读取配置后回答");
        assertThat(trace.path("finalAnswer").asText()).isEqualTo("处理完成");
        assertThat(trace.path("steps")).hasSize(1);
        assertThat(trace.path("steps").get(0).path("reasoning").asText())
                .isEqualTo("需要先获得配置证据");
        assertThat(trace.has("finalReasoning")).isFalse();
        assertThat(protocol).hasSize(4);
        assertThat(protocol.get(0).getRole()).isEqualTo("user");
        assertThat(protocol.get(0).getContent()).isEqualTo("请读取配置");
        assertThat(protocol.get(1).getRole()).isEqualTo("assistant");
        assertThat(protocol.get(1).getContent()).isEqualTo("准备读取配置");
        assertThat(protocol.get(1).getReasoning()).isEqualTo("需要先获得配置证据");
        assertThat(protocol.get(1).getToolCalls()).hasSize(1);
        assertThat(protocol.get(2).getRole()).isEqualTo("tool");
        assertThat(protocol.get(2).getContent()).isEqualTo("配置内容");
        assertThat(protocol.get(3).getRole()).isEqualTo("assistant");
        assertThat(protocol.get(3).getContent()).isEqualTo("处理完成");
        assertThat(protocol.get(3).getReasoning()).isNull();
    }

    /**
     * 会话不存在时不得创建孤立运行记录。
     */
    @Test
    void 会话不存在时应拒绝保存() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        AgentRunPersistenceService service = new AgentRunPersistenceService(
                runRepository, sessionRepository, new ObjectMapper(),
                new AgentProtocolBuilder(),
                new ConversationContextTokenEstimator(new AgentConfigProperties()),
                mock(ConversationContextService.class));
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(
                "missing", "用户消息", null, List.of(), null,
                AgentRunStatus.COMPLETED, null, 0))
                .isInstanceOf(SessionNotFoundException.class);
    }

    /**
     * 快照缺失时应从 agent_run 补偿完整工具协议，并记录最后应用的运行 ID。
     */
    @Test
    void 快照缺失时应从运行账本重建完整工具协议() throws Exception {
        ConversationContextRepository contextRepository = mock(ConversationContextRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        LlmService llmService = mock(LlmService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentConfigProperties properties = new AgentConfigProperties();
        ConversationContextService service = new ConversationContextService(
                contextRepository, runRepository, sessionRepository, objectMapper, llmService,
                new ConversationContextTokenEstimator(properties), properties);

        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setId("session-1");
        AgentRunEntity run = new AgentRunEntity();
        run.setId(7L);
        run.setSession(session);
        run.setProtocolJson(objectMapper.writeValueAsString(AgentRunCheckpoint.fromMessages(List.of(
                ChatMessage.userMessage("读取配置"),
                ChatMessage.assistantToolCallMessage(
                        "准备读取", "需要配置证据",
                        new LlmToolCall("call-1", "read_file", Map.of("path", "config.yml"))),
                ChatMessage.toolMessage("call-1", "配置内容"),
                ChatMessage.assistantMessage("读取完成")))));

        when(contextRepository.findById("session-1")).thenReturn(Optional.empty());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(runRepository.findBySession_IdOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq("session-1"), any(Pageable.class)))
                .thenReturn(List.of(run));

        ConversationContextView view = service.load("session-1");

        assertThat(view.summary()).isEmpty();
        assertThat(view.recentMessages()).hasSize(4);
        assertThat(view.recentMessages().get(1).getContent()).isEqualTo("准备读取");
        assertThat(view.recentMessages().get(1).getReasoning()).isEqualTo("需要配置证据");
        assertThat(view.recentMessages().get(1).getToolCalls()).hasSize(1);
        assertThat(view.recentMessages().get(2).getToolCallId()).isEqualTo("call-1");

        ArgumentCaptor<ConversationContextEntity> contextCaptor =
                ArgumentCaptor.forClass(ConversationContextEntity.class);
        verify(contextRepository, org.mockito.Mockito.atLeastOnce()).save(contextCaptor.capture());
        ConversationContextEntity saved = contextCaptor.getValue();
        assertThat(saved.getLastAppliedRunId()).isEqualTo(7L);
        assertThat(saved.getRecentProtocolTokens()).isPositive();
    }

    /**
     * 超过摘要阈值时应调用模型压缩最旧协议，并把摘要写回会话快照。
     */
    @Test
    void 超过阈值时应生成并持久化长期摘要() throws Exception {
        ConversationContextRepository contextRepository = mock(ConversationContextRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        LlmService llmService = mock(LlmService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getContext().setSummarizeThresholdTokens(1);
        properties.getContext().setCompactTargetTokens(1);
        ConversationContextService service = new ConversationContextService(
                contextRepository, runRepository, sessionRepository, objectMapper, llmService,
                new ConversationContextTokenEstimator(properties), properties);

        List<ChatMessage> protocol = List.of(
                ChatMessage.userMessage("旧任务"),
                ChatMessage.assistantToolCallMessage(
                        "准备检查", "需要读取证据",
                        new LlmToolCall("call-1", "read_file", Map.of("path", "a.txt"))),
                ChatMessage.toolMessage("call-1", "旧证据"),
                ChatMessage.assistantMessage("旧结论"),
                ChatMessage.userMessage("继续处理"),
                ChatMessage.assistantMessage("当前结论"));
        ConversationContextEntity context = new ConversationContextEntity();
        context.setSessionId("session-2");
        context.setSummaryJson(objectMapper.writeValueAsString(ConversationSummary.of("")));
        context.setRecentProtocolJson(objectMapper.writeValueAsString(AgentRunCheckpoint.fromMessages(protocol)));

        when(contextRepository.findById("session-2")).thenReturn(Optional.of(context));
        when(runRepository.findBySession_IdOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq("session-2"), any(Pageable.class)))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("目标：继续处理；证据：已读取 a.txt；待办：无。");

        ConversationContextView view = service.load("session-2");

        assertThat(view.summary()).contains("继续处理", "a.txt");
        assertThat(view.recentMessages()).isNotEmpty();
        assertThat(view.recentMessages().get(0).getRole()).isNotEqualTo("tool");
        ConversationSummary persisted = objectMapper.readValue(
                context.getSummaryJson(), ConversationSummary.class);
        assertThat(persisted.content()).isEqualTo(view.summary());
        verify(llmService).chat(any());
    }
}
