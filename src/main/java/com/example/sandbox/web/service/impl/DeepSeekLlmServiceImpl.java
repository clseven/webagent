package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

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
// @Service("executorLlm") — 已由 GenericLlmServiceImpl 接管，保留此类供需要 thinking 参数时手动切换
public class DeepSeekLlmServiceImpl extends BaseLlmServiceImpl {

    /** 是否在 DeepSeek 请求中启用思考模式。 */
    private final boolean thinkingEnabled;

    /**
     * 使用执行器配置创建 DeepSeek 服务。
     *
     * @param configProperties Agent 配置属性
     * @param objectMapper     JSON 序列化工具
     */
    @Autowired
    public DeepSeekLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getExecutor().getApiUrl(),
                configProperties.getLlm().getExecutor().getApiKey(),
                configProperties.getLlm().getExecutor().getModel(),
                objectMapper,
                new DeepSeekLlmErrorPolicy()
        );
        this.thinkingEnabled = configProperties.getLlm().getExecutor().isThinkingEnabled();
    }

    /**
     * 为 DeepSeek 请求显式设置思考模式，避免依赖服务端默认行为。
     *
     * @param requestBody 即将发送给 DeepSeek 的请求体
     */
    @Override
    protected void customizeRequestBody(Map<String, Object> requestBody) {
        requestBody.put("thinking", Map.of(
                "type", thinkingEnabled ? "enabled" : "disabled"
        ));
    }
}
