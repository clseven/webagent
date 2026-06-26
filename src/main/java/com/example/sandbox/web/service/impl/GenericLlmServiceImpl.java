package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 通用 LLM 服务实现 — 用作执行器 LLM
 *
 * <h3>用途</h3>
 * <p>负责 Agent 的执行阶段，支持 OpenAI 兼容协议的任意模型：</p>
 * <ul>
 *   <li>支持原生 tool_calls</li>
 *   <li>支持多模态消息（图文混合，视觉模型）</li>
 *   <li>无厂商专有参数，配置即用</li>
 * </ul>
 *
 * <h3>配置来源</h3>
 * <p>从 application.yml 的 {@code agent.llm.executor} 节点读取：</p>
 * <ul>
 *   <li>api-url — 模型 API 地址</li>
 *   <li>api-key — 认证密钥</li>
 *   <li>model   — 模型名称</li>
 * </ul>
 *
 * @author example
 */
@Service("executorLlm")
public class GenericLlmServiceImpl extends BaseLlmServiceImpl {

    @Autowired
    public GenericLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getExecutor().getApiUrl(),
                configProperties.getLlm().getExecutor().getApiKey(),
                configProperties.getLlm().getExecutor().getModel(),
                objectMapper
        );
    }
}
