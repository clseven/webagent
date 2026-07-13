package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.SubAgentConfigProperties;
import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.mcpclient.McpClientToolProvider;
import com.example.sandbox.web.service.tool.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证知识库开关会同步控制 Agent 可见的检索工具。
 */
class AgentToolKnowledgeSwitchTest {

    /**
     * 开关关闭时，即使 Agent 关联了知识库，也不能向模型暴露知识库检索工具。
     */
    @Test
    void removesKnowledgeSearchToolWhenKnowledgeIsDisabled() {
        KnowledgeSearchTool knowledgeSearchTool = new KnowledgeSearchTool();
        SandboxService sandboxService = mock(SandboxService.class);
        when(sandboxService.isAioSandbox("session-1")).thenReturn(false);
        AgentToolContextService service = new AgentToolContextService(
                List.<Tool>of(knowledgeSearchTool),
                mock(McpClientToolProvider.class),
                sandboxService,
                new SubAgentConfigProperties());
        AgentAppEntity app = new AgentAppEntity();
        app.setKnowledgeBaseIds(Set.of(11L, 12L));

        try {
            UserContext.setKnowledgeEnabled(false);
            AgentToolContext context = service.build("session-1", app, "两个知识库");

            assertThat(context.filteredTools()).isEmpty();
            assertThat(context.toolDefinitions()).isEmpty();
            assertThat(context.knowledgeSearchTool()).isNull();
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 开关开启时，knowledge_search 必须检索当前 Agent 关联的全部知识库。
     */
    @Test
    void configuresKnowledgeSearchToolWithAllLinkedKnowledgeBases() {
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeSearchTool knowledgeSearchTool = new KnowledgeSearchTool();
        ReflectionTestUtils.setField(knowledgeSearchTool, "knowledgeService", knowledgeService);
        SandboxService sandboxService = mock(SandboxService.class);
        when(sandboxService.isAioSandbox("session-1")).thenReturn(false);
        AgentToolContextService service = new AgentToolContextService(
                List.<Tool>of(knowledgeSearchTool),
                mock(McpClientToolProvider.class),
                sandboxService,
                new SubAgentConfigProperties());
        AgentAppEntity app = new AgentAppEntity();
        app.setKnowledgeBaseIds(Set.of(11L, 12L));
        when(knowledgeService.search(eq(7L), org.mockito.ArgumentMatchers.<List<Long>>any(),
                eq("query"), eq(List.of()), eq(5), isNull())).thenReturn(List.of());

        AgentToolContext context = null;
        try {
            UserContext.setCurrentUserId(7L);
            UserContext.setKnowledgeEnabled(true);
            context = service.build("session-1", app, "两个知识库");

            String output = context.knowledgeSearchTool().execute(
                    "session-1", Map.of("query", "query"));

            assertThat(output).contains("未找到相关知识库内容");
            ArgumentCaptor<List<Long>> kbIds = ArgumentCaptor.forClass(List.class);
            verify(knowledgeService).search(
                    eq(7L), kbIds.capture(), eq("query"), eq(List.of()), eq(5), isNull());
            assertThat(kbIds.getValue()).containsExactlyInAnyOrder(11L, 12L);
        } finally {
            service.clearRuntimeState(context);
            UserContext.clear();
        }
    }
}
