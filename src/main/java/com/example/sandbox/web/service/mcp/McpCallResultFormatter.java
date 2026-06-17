package com.example.sandbox.web.service.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具调用结果格式化器。
 *
 * <p>MCP 工具可能返回 text、image、audio、resource 或 structuredContent。
 * 本类将这些结果转换为当前 ReAct Agent 可作为 observation 消费的字符串。</p>
 */
public final class McpCallResultFormatter {

    /** JSON 序列化器，用于保留结构化结果。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 工具类不允许实例化。
     */
    private McpCallResultFormatter() {
    }

    /**
     * 格式化 AIO MCP 调用响应。
     *
     * @param response AIO 返回的完整响应信封
     * @return 适合追加给 LLM 的 observation 文本
     */
    public static String format(Map<String, Object> response) {
        if (response == null) {
            return "ERROR: MCP 工具返回空响应";
        }
        if (Boolean.FALSE.equals(response.get("success"))) {
            return "ERROR: MCP 工具执行失败：" + response.getOrDefault("message", "未知错误");
        }

        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> result)) {
            return data != null ? data.toString() : "MCP 工具执行成功，但未返回内容";
        }

        StringBuilder builder = new StringBuilder();
        if (Boolean.TRUE.equals(result.get("isError"))) {
            builder.append("ERROR: MCP 工具返回错误：\n");
        }

        appendContent(builder, result.get("content"));

        Object structuredContent = result.get("structuredContent");
        if (structuredContent != null) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("结构化结果：\n").append(toJson(structuredContent));
        }

        if (builder.isEmpty()) {
            builder.append(toJson(result));
        }
        return builder.toString();
    }

    /**
     * 追加 MCP content 数组内容。
     *
     * @param builder 输出文本
     * @param content MCP content 字段
     */
    private static void appendContent(StringBuilder builder, Object content) {
        if (!(content instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                appendContentItem(builder, map);
            } else if (item != null) {
                appendLine(builder, item.toString());
            }
        }
    }

    /**
     * 追加单个 MCP content 项。
     *
     * @param builder 输出文本
     * @param item    MCP content 项
     */
    private static void appendContentItem(StringBuilder builder, Map<?, ?> item) {
        Object type = item.get("type");
        if ("text".equals(type)) {
            Object text = item.get("text");
            appendLine(builder, text != null ? text.toString() : "");
            return;
        }
        if ("image".equals(type)) {
            appendLine(builder, "[image omitted: " + item.get("mimeType") + "]");
            return;
        }
        if ("audio".equals(type)) {
            appendLine(builder, "[audio omitted: " + item.get("mimeType") + "]");
            return;
        }
        if ("resource".equals(type)) {
            appendLine(builder, formatResource(item.get("resource")));
            return;
        }
        appendLine(builder, toJson(item));
    }

    /**
     * 格式化 MCP resource 内容。
     *
     * @param resource resource 字段
     * @return 资源摘要或文本内容
     */
    private static String formatResource(Object resource) {
        if (!(resource instanceof Map<?, ?> map)) {
            return String.valueOf(resource);
        }
        Object text = map.get("text");
        if (text != null) {
            return text.toString();
        }
        return "[resource omitted: uri=" + map.get("uri") + ", mimeType=" + map.get("mimeType") + "]";
    }

    /**
     * 向输出追加一行文本。
     *
     * @param builder 输出文本
     * @param line    待追加文本
     */
    private static void appendLine(StringBuilder builder, String line) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(line);
    }

    /**
     * 将对象转换为 JSON 文本。
     *
     * @param value 原始对象
     * @return JSON 文本；序列化失败时返回对象字符串
     */
    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
