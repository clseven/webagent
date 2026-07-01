package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 规划 LLM 服务实现。
 *
 * <h3>用途</h3>
 * <p>当前主任务规划由 {@link AgentPlannerService} 沿用执行器模型完成，
 * 该 Bean 主要保留给会话标题等轻量规划类调用。</p>
 * <ul>
 *   <li>不需要工具调用能力</li>
 *   <li>调用频率较低</li>
 *   <li>可通过配置切换到 OpenAI 兼容的规划模型</li>
 * </ul>
 *
 * <h3>配置来源</h3>
 * <p>从 application.yml 的 agent.llm.planner 节点读取：</p>
 * <ul>
 *   <li>api-url — 规划模型 API 地址</li>
 *   <li>api-key — 认证密钥</li>
 *   <li>model — 模型名称</li>
 * </ul>
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
