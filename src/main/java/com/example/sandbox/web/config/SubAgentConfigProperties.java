package com.example.sandbox.web.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子代理配置属性
 *
 * <p>控制 {@code run_subagent} 工具中各子代理类型的行为：
 * 每种子代理类型有不同的工具限制、最大迭代次数和专属系统提示词。</p>
 *
 * @author example
 * @date 2026/06/23
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "agent.sub-agent")
public class SubAgentConfigProperties {

    /** 总开关，false 时 run_subagent 工具不注册 */
    private boolean enabled = false;

    /** 单个子代理的超时时间（秒） */
    private int timeoutSeconds = 120;

    /** 各类型子代理配置 */
    private Map<String, TypeConfig> types = new LinkedHashMap<>();

    @Setter
    @Getter
    public static class TypeConfig {
        /** 该类型是否启用 */
        private boolean enabled = true;

        /** 最大迭代次数 */
        private int maxIterations = 10;
    }

    /**
     * 获取指定类型的配置，如果不存在或未启用则返回 null。
     */
    public TypeConfig getTypeConfig(String type) {
        TypeConfig config = types.get(type);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        return config;
    }
}
