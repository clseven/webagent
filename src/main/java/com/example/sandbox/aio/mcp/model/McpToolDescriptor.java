package com.example.sandbox.aio.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具描述模型。
 *
 * <p>该模型承载 AIO Sandbox 从 MCP Server 返回的工具元数据，
 * 上层会将其转换为项目内部的 {@code ToolDefinition}。</p>
 *
 * @param name         MCP Server 内的原始工具名称
 * @param title        工具展示标题，可为空
 * @param description  工具能力说明，可为空
 * @param inputSchema  工具输入参数 JSON Schema
 * @param outputSchema 工具输出参数 JSON Schema，可为空
 */
public record McpToolDescriptor(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
) {

    /**
     * 从 AIO 返回的通用 Map 构造 MCP 工具描述。
     *
     * @param source AIO 响应中 tools 数组的单个元素
     * @return 规范化后的 MCP 工具描述
     * @throws IllegalArgumentException 当工具名称缺失时抛出，避免注册不可调用工具
     */
    public static McpToolDescriptor fromMap(Map<?, ?> source) {
        Object nameValue = source.get("name");
        if (nameValue == null || nameValue.toString().isBlank()) {
            throw new IllegalArgumentException("MCP 工具名称不能为空");
        }
        return new McpToolDescriptor(
                nameValue.toString(),
                stringValue(source.get("title")),
                stringValue(source.get("description")),
                mapValue(source.get("inputSchema")),
                mapValue(source.get("outputSchema"))
        );
    }

    /**
     * 获取可直接传给 LLM 的输入参数 schema。
     *
     * <p>当 MCP Server 没有返回 schema 时，使用无参数 object schema 兜底，
     * 避免传给模型的工具定义出现 null。</p>
     *
     * @return 输入参数 JSON Schema
     */
    public Map<String, Object> normalizedInputSchema() {
        if (inputSchema != null && !inputSchema.isEmpty()) {
            return inputSchema;
        }
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    /**
     * 将任意对象转换为字符串。
     *
     * @param value 原始对象
     * @return 字符串值；原始对象为空时返回 null
     */
    private static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * 将任意对象安全转换为字符串键 Map。
     *
     * @param value 原始对象
     * @return 字符串键 Map；原始对象不是 Map 时返回空 Map
     */
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }
}
