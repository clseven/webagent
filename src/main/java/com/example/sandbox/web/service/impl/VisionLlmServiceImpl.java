package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 视觉 LLM 服务实现。
 *
 * <p>该服务专门用于图片观察场景，默认读取 Agnes 配置。它只使用通用
 * OpenAI 兼容请求格式，不注入 DeepSeek 的 {@code thinking} 等厂商专有参数。</p>
 */
@Service("visionLlm")
public class VisionLlmServiceImpl extends BaseLlmServiceImpl {

    /**
     * 使用视觉模型配置创建服务实例。
     *
     * @param configProperties Agent 配置属性
     * @param objectMapper     JSON 序列化工具
     */
    @Autowired
    public VisionLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getVision().getApiUrl(),
                configProperties.getLlm().getVision().getApiKey(),
                configProperties.getLlm().getVision().getModel(),
                objectMapper
        );
    }
}
