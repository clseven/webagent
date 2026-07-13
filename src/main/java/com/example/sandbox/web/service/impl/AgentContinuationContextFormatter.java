package com.example.sandbox.web.service.impl;

import java.util.List;
import java.util.Map;

/**
 * 将持久化展示事件转换为模型可读的续接资料。
 *
 * <p>该转换只用于规划提示和缺少协议检查点的旧超限数据，不把展示事件伪装成
 * 原生 tool calling 消息。单个长字段保留头尾，防止旧工具输出撑爆下一轮上下文。</p>
 */
public final class AgentContinuationContextFormatter {

    /** 单个事件字段写入续接资料的最大字符数。 */
    private static final int MAX_FIELD_CHARS = 2_000;

    private AgentContinuationContextFormatter() {
    }

    /**
     * 格式化上轮展示事件。
     *
     * @param events plan、thinking、reasoning、toolResult 等事件
     * @return 可注入规划器和执行器动态上下文的中文说明
     */
    public static String format(List<Map<String, Object>> events) {
        StringBuilder output = new StringBuilder();
        output.append("## 上轮暂停运行续接资料\n");
        output.append("上轮达到最大迭代次数。以下是已完成过程，请基于这些证据继续，避免重复执行已有副作用。\n");
        if (events == null || events.isEmpty()) {
            output.append("（没有可恢复的展示事件，请先检查当前工作区和任务状态。）");
            return output.toString();
        }

        for (Map<String, Object> event : events) {
            if (event == null) {
                continue;
            }
            String type = String.valueOf(event.getOrDefault("type", ""));
            switch (type) {
                case "plan" -> output.append("\n### 原执行计划\n")
                        .append(compact(event.get("content")));
                case "thinking" -> appendStepText(output, "公开思考", event);
                case "reasoning" -> appendStepText(output, "推理记录", event);
                case "toolResult" -> appendToolResult(output, event);
                default -> {
                    // 状态和前端专用事件不参与模型续接，避免注入无关 UI 数据。
                }
            }
        }
        return output.toString();
    }

    /**
     * 追加带步骤号的思考文本。
     *
     * @param output 输出缓冲区
     * @param label  内容标签
     * @param event  展示事件
     */
    private static void appendStepText(StringBuilder output, String label, Map<String, Object> event) {
        output.append("\n### 第 ")
                .append(event.getOrDefault("stepIndex", "?"))
                .append(" 步").append(label).append("\n")
                .append(compact(event.get("content")));
    }

    /**
     * 追加工具名称、参数和结果。
     *
     * @param output 输出缓冲区
     * @param event  toolResult 展示事件
     */
    private static void appendToolResult(StringBuilder output, Map<String, Object> event) {
        output.append("\n### 已执行工具：")
                .append(event.getOrDefault("tool", "unknown"))
                .append("\n参数：").append(compact(event.get("args")))
                .append("\n结果：").append(compact(event.get("result")));
    }

    /**
     * 压缩单个长字段，保留头尾和省略长度。
     *
     * @param value 原始字段值
     * @return 最大约两千字符的可读文本
     */
    private static String compact(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.length() <= MAX_FIELD_CHARS) {
            return text;
        }
        int half = MAX_FIELD_CHARS / 2;
        return text.substring(0, half)
                + "\n...（省略 " + (text.length() - MAX_FIELD_CHARS) + " 字符）...\n"
                + text.substring(text.length() - half);
    }
}
