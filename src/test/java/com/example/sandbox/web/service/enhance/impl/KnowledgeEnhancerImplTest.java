package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Agent 自动预检索复用统一的多知识库检索入口。
 */
class KnowledgeEnhancerImplTest {

    /**
     * 验证自动预检索把全部知识库和对话历史交给统一服务，并只负责格式化结果。
     */
    @Test
    void delegatesAllKnowledgeBasesToUnifiedSearch() {
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        RagConfigProperties config = new RagConfigProperties();
        config.getEnhancement().setEnabled(true);
        config.getEnhancement().getRerank().setTopK(5);
        config.getEnhancement().getRerank().setMinScore(0.8f);
        KnowledgeEnhancerImpl enhancer = new KnowledgeEnhancerImpl();
        ReflectionTestUtils.setField(enhancer, "config", config);
        ReflectionTestUtils.setField(enhancer, "knowledgeService", knowledgeService);

        List<Long> kbIds = List.of(11L, 12L);
        List<ChatMessage> history = List.of(ChatMessage.restore("user", "上一轮", null, 1L));
        when(knowledgeService.search(
                eq(7L), eq(kbIds), eq("当前问题"), eq(history), eq(5), eq(0.8f)))
                .thenReturn(List.of(Map.of(
                        "docId", 1L,
                        "docName", "项目说明.pdf",
                        "chunkIndex", 2,
                        "content", "项目正文",
                        "score", 0.95f)));

        String context = enhancer.enhance(7L, kbIds, "当前问题", history);

        assertThat(context).contains("项目说明.pdf", "项目正文", "0.95");
        verify(knowledgeService).search(
                7L, kbIds, "当前问题", history, 5, 0.8f);
    }
}
