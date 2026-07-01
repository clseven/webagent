package com.example.sandbox.web.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConfigPropertiesVisionTest {

    @Test
    void 默认模型角色应将DeepSeek用于执行并将Agnes用于视觉观察() {
        AgentConfigProperties properties = new AgentConfigProperties();

        assertThat(properties.getLlm().getExecutor().getApiUrl())
                .contains("deepseek");
        assertThat(properties.getLlm().getExecutor().getModel())
                .contains("deepseek");
        assertThat(properties.getLlm().getExecutor().isThinkingEnabled())
                .isFalse();

        assertThat(properties.getLlm().getVision().getApiUrl())
                .contains("agnes");
        assertThat(properties.getLlm().getVision().getModel())
                .isEqualTo("agnes-2.0-flash");
    }
}
