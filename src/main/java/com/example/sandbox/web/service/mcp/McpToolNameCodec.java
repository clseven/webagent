package com.example.sandbox.web.service.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MCP 工具名称编码器。
 *
 * <p>LLM 工具名只能使用字母、数字和下划线，本类将 MCP 原始
 * server/tool 名称转换为 {@code mcp__server__tool} 风格的安全工具名。</p>
 */
public final class McpToolNameCodec {

    /** LLM 工具名允许的最大长度，兼容常见 OpenAI 风格接口。 */
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    /** 非工具名字符匹配器。 */
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-zA-Z0-9_]+");

    /** 多个下划线匹配器。 */
    private static final Pattern MULTIPLE_UNDERSCORES = Pattern.compile("_+");

    /**
     * 工具类不允许实例化。
     */
    private McpToolNameCodec() {
    }

    /**
     * 生成模型可见的 MCP 工具名。
     *
     * @param serverName MCP Server 名称
     * @param toolName   MCP 原始工具名
     * @return 安全工具名，格式近似 {@code mcp__server__tool}
     */
    public static String toToolName(String serverName, String toolName) {
        String base = "mcp__" + sanitize(serverName) + "__" + sanitize(toolName);
        if (base.length() <= MAX_TOOL_NAME_LENGTH) {
            return base;
        }

        String hash = shortHash(serverName + "/" + toolName);
        int prefixLength = MAX_TOOL_NAME_LENGTH - hash.length() - 1;
        return trimUnderscore(base.substring(0, Math.max(4, prefixLength))) + "_" + hash;
    }

    /**
     * 生成带冲突后缀的工具名。
     *
     * @param serverName MCP Server 名称
     * @param toolName   MCP 原始工具名
     * @param index      冲突序号
     * @return 带序号的安全工具名
     */
    public static String toCollisionName(String serverName, String toolName, int index) {
        String suffix = "_" + shortHash(serverName + "/" + toolName + "#" + index);
        String base = toToolName(serverName, toolName);
        int prefixLength = MAX_TOOL_NAME_LENGTH - suffix.length();
        if (base.length() > prefixLength) {
            base = trimUnderscore(base.substring(0, prefixLength));
        }
        return base + suffix;
    }

    /**
     * 将原始名称清理成下划线风格片段。
     *
     * @param value 原始名称
     * @return 清理后的名称片段
     */
    private static String sanitize(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value;
        normalized = INVALID_CHARS.matcher(normalized).replaceAll("_");
        normalized = MULTIPLE_UNDERSCORES.matcher(normalized).replaceAll("_");
        normalized = trimUnderscore(normalized);
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * 移除首尾下划线。
     *
     * @param value 原始文本
     * @return 去除首尾下划线后的文本
     */
    private static String trimUnderscore(String value) {
        String result = value;
        while (result.startsWith("_")) {
            result = result.substring(1);
        }
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 生成短哈希，用于超长名称和冲突名称。
     *
     * @param value 原始文本
     * @return 八位十六进制哈希
     */
    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
