package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DeepSeek LLM 服务实现 — 用作执行器 LLM
 *
 * <h3>用途</h3>
 * <p>负责 Agent 的执行阶段，特点：</p>
 * <ul>
 *   <li>支持原生 tool_calls（OpenAI 兼容）</li>
 *   <li>响应速度快，适合频繁的工具调用</li>
 *   <li>成本较低，适合 ReAct 循环的多轮调用</li>
 * </ul>
 *
 * <h3>配置来源</h3>
 * <p>从 application.yml 的 agent.llm.executor 节点读取：</p>
 * <ul>
 *   <li>api-url — DeepSeek API 地址</li>
 *   <li>api-key — 认证密钥</li>
 *   <li>model — 模型名称（如 deepseek-chat）</li>
 * </ul>
 *
 * @author example
 * @date 2026/05/14
 */
@Service("executorLlm")
public class DeepSeekLlmServiceImpl extends BaseLlmServiceImpl {

    @Autowired
    public DeepSeekLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getExecutor().getApiUrl(),
                configProperties.getLlm().getExecutor().getApiKey(),
                configProperties.getLlm().getExecutor().getModel(),
                objectMapper
        );
    }
}
