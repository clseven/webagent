package com.example.sandbox.web.service.mcpclient;

import java.util.List;
import java.util.Map;

/**
 * MCP 管理结果格式化器。
 *
 * <p>把结构化加载结果和 Server 状态转换为适合 Agent 继续推理的中文文本，
 * 同时避免输出 headers、环境变量和其他敏感配置。</p>
 */
public final class McpManagementResultFormatter {

    /** 工具类不允许实例化。 */
    private McpManagementResultFormatter() {
    }

    /**
     * 格式化 MCP 重新加载结果。
     *
     * @param result 重新加载结果
     * @return Agent 可读的加载摘要
     */
    public static String formatReload(McpReloadResult result) {
        StringBuilder text = new StringBuilder(result.successful()
                ? "MCP 配置处理完成。\n"
                : "MCP 配置已保存，但连接或加载未完全成功。\n");
        appendList(text, "新增", result.added());
        appendList(text, "更新", result.updated());
        appendList(text, "移除", result.removed());
        appendList(text, "未变化", result.unchanged());
        if (result.reused() != null && !result.reused().isEmpty()) {
            text.append("复用已有配置：\n");
            for (Map.Entry<String, String> entry : result.reused().entrySet()) {
                text.append("- 请求 ID ").append(entry.getKey())
                        .append(" → 已有 ID ").append(entry.getValue())
                        .append("（endpoint 相同，未创建重复 Server）\n");
            }
        }

        if (result.failed() != null && !result.failed().isEmpty()) {
            text.append("失败：\n");
            for (Map.Entry<String, McpOperationError> entry : result.failed().entrySet()) {
                McpOperationError error = entry.getValue();
                text.append("- ").append(entry.getKey())
                        .append("：[").append(error.code()).append("] ")
                        .append(error.message());
                if (error.detail() != null && !error.detail().isBlank()) {
                    text.append("\n  详情：").append(error.detail());
                }
                text.append('\n');
            }
        }

        if (result.successful()
                && (!result.added().isEmpty() || !result.updated().isEmpty())) {
            text.append("新 MCP 工具将在下一条用户消息中加入 Agent 工具列表。");
        }
        return text.toString().trim();
    }

    /**
     * 格式化系统和用户 MCP Server 状态。
     *
     * @param servers Server 安全状态列表
     * @return Agent 可读的 Server 清单
     */
    public static String formatServers(List<McpServerView> servers) {
        if (servers == null || servers.isEmpty()) {
            return "当前没有配置任何 MCP Server。";
        }

        StringBuilder text = new StringBuilder("当前 MCP Server：\n");
        for (McpServerView server : servers) {
            text.append("\n- [").append(server.scope()).append("] ")
                    .append(server.id())
                    .append("：")
                    .append(server.connected() ? "已连接" : "未连接")
                    .append(server.enabled() ? "" : "（已禁用）")
                    .append("\n  transport：").append(server.type());
            if (server.url() != null && !server.url().isBlank()) {
                text.append("\n  url：").append(server.url());
            }
            text.append("\n  工具数：").append(server.toolNames().size());
            if (!server.toolNames().isEmpty()) {
                List<String> visible = server.toolNames().stream().limit(20).toList();
                text.append("\n  工具：").append(String.join(", ", visible));
                if (server.toolNames().size() > visible.size()) {
                    text.append(" 等");
                }
            }
            if (server.lastError() != null) {
                text.append("\n  最近错误：[").append(server.lastError().code())
                        .append("] ").append(server.lastError().message());
                if (server.lastError().detail() != null
                        && !server.lastError().detail().isBlank()) {
                    text.append("\n  错误详情：").append(server.lastError().detail());
                }
            }
            text.append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 追加非空结果列表。
     *
     * @param text  输出文本
     * @param label 列表标签
     * @param items 结果项
     */
    private static void appendList(StringBuilder text, String label, List<String> items) {
        if (items != null && !items.isEmpty()) {
            text.append(label).append("：")
                    .append(String.join(", ", items)).append('\n');
        }
    }
}
