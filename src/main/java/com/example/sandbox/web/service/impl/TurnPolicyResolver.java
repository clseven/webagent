package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轮次策略解析器，将分类结果映射为可执行的策略开关。
 *
 * <p>当前实现为纯映射（SOCIAL → LITE，TASK/AMBIGUOUS → FULL）。
 * 后续可在此加入用户级别覆盖（如用户设置"始终全能力"）。</p>
 */
@Component
public class TurnPolicyResolver {

    private final TurnModeClassifier classifier;

    public TurnPolicyResolver(TurnModeClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 解析本轮策略。
     *
     * @param userMessage 用户当前输入
     * @param history     当前会话历史
     * @return 本轮策略
     */
    public TurnPolicy resolve(String userMessage, List<ChatMessage> history) {
        TurnMode mode = classifier.classify(userMessage, history);
        return TurnPolicy.forMode(mode);
    }
}
