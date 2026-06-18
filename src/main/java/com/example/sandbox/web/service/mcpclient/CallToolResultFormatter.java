package com.example.sandbox.web.service.mcpclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * 把 MCP {@link McpSchema.CallToolResult} 转换成 Agent observation 文本。
 *
 * <p>类似 {@code McpCallResultFormatter}，但处理的是强类型 SDK 对象，而不是
 * AIO 沙箱返回的 Map。</p>
 */
final class CallToolResultFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CallToolResultFormatter() {
    }

    /**
     * 把 CallToolResult 拼成可读文本。
     *
     * @param result MCP 调用结果
     * @return observation 文本
     */
    static String format(McpSchema.CallToolResult result) {
        if (result == null) {
            return "ERROR: MCP 工具未返回结果";
        }

        StringBuilder builder = new StringBuilder();
        if (Boolean.TRUE.equals(result.isError())) {
            builder.append("ERROR: MCP 工具返回错误：\n");
        }

        appendContent(builder, result.content());

        Object structured = result.structuredContent();
        if (structured != null) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("结构化结果：\n").append(toJson(structured));
        }

        if (builder.isEmpty()) {
            builder.append("MCP 工具执行成功，但未返回内容");
        }
        return builder.toString();
    }

    private static void appendContent(StringBuilder builder, List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        for (McpSchema.Content item : contents) {
            if (item == null) {
                continue;
            }
            if (item instanceof McpSchema.TextContent text) {
                appendLine(builder, text.text() != null ? text.text() : "");
            } else if (item instanceof McpSchema.ImageContent image) {
                appendLine(builder, "[image omitted: " + image.mimeType() + "]");
            } else if (item instanceof McpSchema.EmbeddedResource embedded) {
                appendLine(builder, formatResource(embedded.resource()));
            } else {
                // AudioContent、ResourceLink 等其它变体，统一序列化兜底
                appendLine(builder, toJson(item));
            }
        }
    }

    private static String formatResource(McpSchema.ResourceContents resource) {
        if (resource == null) {
            return "[empty resource]";
        }
        if (resource instanceof McpSchema.TextResourceContents text) {
            return text.text() != null ? text.text() : "";
        }
        // BlobResourceContents 等：仅打摘要，不把 base64 塞进上下文
        return "[resource omitted: " + toJson(resource) + "]";
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(line);
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
