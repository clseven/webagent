package com.example.sandbox.web.service.mcpclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * 把 MCP {@link McpSchema.CallToolResult} 转换成 Agent observation 文本。
 *
 * <p>处理的是强类型 SDK 对象（{@code McpSchema.CallToolResult}）。</p>
 */
final class CallToolResultFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CallToolResultFormatter() {
    }

    /**
     * 把 CallToolResult 拼成可读文本。
     *
     * <p>图片（{@link McpSchema.ImageContent}）不在本方法处理，由 {@link #extractImages}
     * 单独抽出后交由视觉 Hook 处理；本方法对图片 content 静默跳过，避免把 base64
     * 塞进文本上下文。</p>
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
            } else if (item instanceof McpSchema.ImageContent) {
                // 图片由 extractImages 单独抽出交视觉 Hook 处理，文本中不输出 base64
                continue;
            } else if (item instanceof McpSchema.EmbeddedResource embedded) {
                appendLine(builder, formatResource(embedded.resource()));
            } else {
                // AudioContent、ResourceLink 等其它变体，统一序列化兜底
                appendLine(builder, toJson(item));
            }
        }
    }

    /**
     * 从 MCP 调用结果中抽出所有图片，供视觉观察 Hook 处理。
     *
     * <p>base64 解码为原始字节后返回。无图片时返回空列表。</p>
     *
     * @param result MCP 调用结果
     * @return 图片列表（bytes + mimeType + 来源标签）
     */
    static List<McpImage> extractImages(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return List.of();
        }
        List<McpImage> images = new java.util.ArrayList<>();
        int index = 0;
        for (McpSchema.Content item : result.content()) {
            if (item instanceof McpSchema.ImageContent image
                    && image.data() != null && !image.data().isEmpty()) {
                try {
                    byte[] bytes = java.util.Base64.getDecoder().decode(image.data());
                    String mimeType = image.mimeType() != null ? image.mimeType() : "image/png";
                    images.add(new McpImage(bytes, mimeType, "MCP image #" + (++index)));
                } catch (IllegalArgumentException e) {
                    // base64 非法时跳过这张，不阻断其它内容
                }
            }
        }
        return List.copyOf(images);
    }

    /**
     * MCP 抽出的图片数据。
     *
     * @param bytes    图片原始字节
     * @param mimeType MIME 类型
     * @param label    来源标签，用于日志和视觉提示
     */
    record McpImage(byte[] bytes, String mimeType, String label) {
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
