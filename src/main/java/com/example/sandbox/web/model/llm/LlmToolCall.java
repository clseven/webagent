package com.example.sandbox.web.model.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * LLM 原生工具调用
 *
 * <p>对应 OpenAI API 响应中 message.tool_calls[] 的单个元素。</p>
 *
 * @param id        工具调用 ID（用于 tool 角色消息关联）
 * @param name      工具名称
 * @param arguments 工具参数
 */
public record LlmToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 创建 Builder 用于流式累积
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 流式构建器
     *
     * <p>流式响应中，工具调用的 name 和 arguments 可能跨多个 chunk 发送，
     * 需要累积拼接。</p>
     */
    public static class Builder {
        private String id;
        private String name;
        private final StringBuilder argumentsBuilder = new StringBuilder();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 追加 arguments JSON 片段
         *
         * <p>流式响应中 arguments 是 JSON 字符串，可能分段到达。</p>
         */
        public Builder appendArguments(String fragment) {
            this.argumentsBuilder.append(fragment);
            return this;
        }

        public String getName() {
            return name;
        }

        /**
         * 构建最终的 LlmToolCall
         *
         * <p>解析累积的 arguments JSON 字符串为 Map。</p>
         */
        public LlmToolCall build() {
            Map<String, Object> args = null;
            String argsJson = argumentsBuilder.toString();
            if (!argsJson.isEmpty()) {
                try {
                    args = MAPPER.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    // 解析失败，返回空 Map
                    args = Map.of();
                }
            }
            return new LlmToolCall(id, name, args != null ? args : Map.of());
        }
    }
}
