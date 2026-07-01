package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通用 LLM 服务实现。
 *
 * <h3>用途</h3>
 * <p>支持 OpenAI 兼容协议的任意模型。当前执行器由
 * {@link DeepSeekLlmServiceImpl} 提供，视觉模型由 {@link VisionLlmServiceImpl}
 * 提供。本类保留为手动扩展或本地调试时复用。</p>
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
 */
public class GenericLlmServiceImpl extends BaseLlmServiceImpl {

    public GenericLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getExecutor().getApiUrl(),
                configProperties.getLlm().getExecutor().getApiKey(),
                configProperties.getLlm().getExecutor().getModel(),
                objectMapper
        );
    }
}
