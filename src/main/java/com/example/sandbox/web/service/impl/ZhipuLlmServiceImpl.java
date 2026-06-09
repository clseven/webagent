package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 智谱 AI LLM 服务实现 — 用作规划器 LLM
 *
 * <h3>用途</h3>
 * <p>负责 Agent 的规划阶段，特点：</p>
 * <ul>
 *   <li>擅长中文理解和结构化输出</li>
 *   <li>规划阶段不需要工具调用，调用频率低</li>
 *   <li>产出的计划质量高，逻辑清晰</li>
 * </ul>
 *
 * <h3>配置来源</h3>
 * <p>从 application.yml 的 agent.llm.planner 节点读取：</p>
 * <ul>
 *   <li>api-url — 智谱 API 地址</li>
 *   <li>api-key — 认证密钥</li>
 *   <li>model — 模型名称（如 glm-4）</li>
 * </ul>
 *
 * @author example
 * @date 2026/05/14
 */
@Service("plannerLlm")
public class ZhipuLlmServiceImpl extends BaseLlmServiceImpl {

    @Autowired
    public ZhipuLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getPlanner().getApiUrl(),
                configProperties.getLlm().getPlanner().getApiKey(),
                configProperties.getLlm().getPlanner().getModel(),
                objectMapper
        );
    }
}
