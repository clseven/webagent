package com.example.sandbox.web.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 RAG 重排配置与主流程模型配置相互独立。
 */
class RerankConfigurationTest {

    /**
     * 验证主流程使用 Pro 时，重排仍默认使用独立 Flash 模型。
     */
    @Test
    void rerankModelIsIndependentFromMainModel() {
        AgentConfigProperties agent = new AgentConfigProperties();
        agent.getLlm().getExecutor().setModel("deepseek-v4-pro");
        RagConfigProperties.Enhancement.Rerank rerank =
                new RagConfigProperties().getEnhancement().getRerank();

        assertThat(agent.getLlm().getExecutor().getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(rerank.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(rerank.getProvider()).isEqualTo("deepseek");
    }

    /**
     * 验证重排请求体和延迟限制具有安全默认值。
     */
    @Test
    void rerankHasBoundedDefaults() {
        RagConfigProperties.Enhancement.Rerank rerank =
                new RagConfigProperties().getEnhancement().getRerank();

        assertThat(rerank.getTopK()).isEqualTo(5);
        assertThat(rerank.getMinScore()).isEqualTo(0.8f);
        assertThat(rerank.getMaxCandidates()).isEqualTo(12);
        assertThat(rerank.getMaxContentChars()).isEqualTo(1200);
        assertThat(rerank.getTimeoutSeconds()).isEqualTo(10);
        assertThat(rerank.getMaxTokens()).isEqualTo(512);
    }
}
