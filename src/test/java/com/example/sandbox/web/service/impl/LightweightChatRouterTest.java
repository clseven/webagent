package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证轻量对话路由只判断是否可以跳过规划阶段。
 */
class LightweightChatRouterTest {

    /**
     * 纯问候应跳过规划，但仍交给 ReactAgent 生成回复。
     */
    @Test
    void skipsPlanningForPureGreeting() {
        LightweightChatRouter router = new LightweightChatRouter(new TurnModeClassifier());

        assertThat(router.shouldSkipPlanning("你好", List.of())).isTrue();
    }

    /**
     * 英文问候和常见标点应被规范化后识别。
     */
    @Test
    void normalizesEnglishGreetingAndPunctuationBeforeSkippingPlanning() {
        LightweightChatRouter router = new LightweightChatRouter(new TurnModeClassifier());

        assertThat(router.shouldSkipPlanning("  Hi！ ", List.of())).isTrue();
    }

    /**
     * 带明确任务意图的输入不能走轻量回复，否则会跳过工具和环境观察。
     */
    @Test
    void keepsPlanningAvailableForTaskLikeMessages() {
        LightweightChatRouter router = new LightweightChatRouter(new TurnModeClassifier());

        assertThat(router.shouldSkipPlanning("你好，帮我看一下文件", List.of())).isFalse();
        assertThat(router.shouldSkipPlanning("搜索一下最新文档", List.of())).isFalse();
        assertThat(router.shouldSkipPlanning("打开网页看看", List.of())).isFalse();
    }

    /**
     * 上下文依赖的确认词必须交给原有 Agent 链路处理。
     */
    @Test
    void keepsPlanningAvailableForContextDependentAcknowledgements() {
        LightweightChatRouter router = new LightweightChatRouter(new TurnModeClassifier());
        List<ChatMessage> history = List.of(ChatMessage.assistantMessage("我可以安装这个 MCP，是否确认？"));

        assertThat(router.shouldSkipPlanning("可以", history)).isFalse();
        assertThat(router.shouldSkipPlanning("确认", history)).isFalse();
        assertThat(router.shouldSkipPlanning("继续", history)).isFalse();
    }

    /**
     * 致谢和告别可以跳过规划，由 ReactAgent 直接回复。
     */
    @Test
    void skipsPlanningForThanksAndFarewell() {
        LightweightChatRouter router = new LightweightChatRouter(new TurnModeClassifier());

        assertThat(router.shouldSkipPlanning("谢谢", List.of())).isTrue();
        assertThat(router.shouldSkipPlanning("再见", List.of())).isTrue();
    }
}
