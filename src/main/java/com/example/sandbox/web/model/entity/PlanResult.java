package com.example.sandbox.web.model.entity;

import com.example.sandbox.web.model.llm.LlmUsage;

/**
 * 规划结果，包含计划内容和 token 用量
 */
public class PlanResult {

    private final String plan;
    private final LlmUsage tokenUsage;

    public PlanResult(String plan, LlmUsage tokenUsage) {
        this.plan = plan;
        this.tokenUsage = tokenUsage;
    }

    public String getPlan() {
        return plan;
    }

    public LlmUsage getTokenUsage() {
        return tokenUsage;
    }
}
