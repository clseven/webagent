package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.llm.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek V4 DSML 工具调用解析器。
 *
 * <p>DeepSeek V4 原生使用 DSML 表达工具调用。正常情况下模型服务会把 DSML 转成
 * OpenAI 兼容的 {@code tool_calls}；部分兼容层会把原始 DSML 放进 {@code content}。
 * 本类在 LLM 协议适配层恢复这些工具调用，同时只向上游返回 DSML 之外的可读文字。</p>
 *
 * <p>解析器按单次模型响应创建，能够识别跨流式 chunk 拆分的开始和结束标记。
 * 普通思考或过渡文字会立即返回，只有可能构成 DSML 标记的短后缀会暂存。</p>
 */
final class DeepSeekDsmlStreamParser {

    /** 单个 DSML 工具调用块允许缓存的最大字符数，防止异常响应无限占用内存。 */
    private static final int MAX_PROTOCOL_CHARS = 2_000_000;

    /** 官方标记及已在线上观察到的全角双竖线、ASCII 兼容变体。 */
    private static final List<MarkerPair> MARKER_PAIRS = List.of(
            new MarkerPair("<｜DSML｜tool_calls>", "</｜DSML｜tool_calls>"),
            new MarkerPair("<｜｜DSML｜｜tool_calls>", "</｜｜DSML｜｜tool_calls>"),
            new MarkerPair("<|DSML|tool_calls>", "</|DSML|tool_calls>"),
            new MarkerPair("<||DSML||tool_calls>", "</||DSML||tool_calls>")
    );

    /** DSML 标记主体变体，较长形式必须先归一化。 */
    private static final List<String> DSML_TOKEN_VARIANTS = List.of(
            "｜｜DSML｜｜", "||DSML||", "｜DSML｜", "|DSML|"
    );

    /** 归一化后的 invoke 块。 */
    private static final Pattern INVOKE_PATTERN = Pattern.compile(
            "<DSML_invoke\\s+([^>]*)>(.*?)</DSML_invoke>", Pattern.DOTALL);

    /** 归一化后的 parameter 块。 */
    private static final Pattern PARAMETER_PATTERN = Pattern.compile(
            "<DSML_parameter\\s+([^>]*)>(.*?)</DSML_parameter>", Pattern.DOTALL);

    /** DSML 标签属性，官方格式使用双引号。 */
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"");

    /** JSON 解析器，用于恢复 {@code string="false"} 的类型。 */
    private final ObjectMapper objectMapper;

    /** 尚未确定是普通文字还是 DSML 开始标记的短文本。 */
    private final StringBuilder pendingText = new StringBuilder();

    /** 当前正在收集的完整 DSML 工具调用块。 */
    private final StringBuilder protocolBuffer = new StringBuilder();

    /** 已从当前模型响应中恢复的工具调用，保持模型原始顺序。 */
    private final List<LlmToolCall> recoveredToolCalls = new ArrayList<>();

    /** 当前是否已经进入 DSML 工具调用块。 */
    private boolean capturingProtocol;

    /** 当前响应是否出现过 DSML 工具调用协议。 */
    private boolean protocolDetected;

    /** 当前解析器是否已经完成，完成后禁止继续追加。 */
    private boolean completed;

    /**
     * 创建单次模型响应使用的 DSML 流式解析器。
     *
     * @param objectMapper JSON 解析器
     */
    DeepSeekDsmlStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 接收一个流式文字片段，返回可以立即展示的普通文字。
     *
     * @param fragment content 或 reasoning_content 的本次增量
     * @return 不含 DSML 的可见文字片段
     * @throws ProtocolException DSML 已完整出现但结构或参数类型非法时抛出
     */
    List<String> accept(String fragment) {
        if (completed) {
            throw new IllegalStateException("DSML 解析器已经完成，不能继续追加内容");
        }
        if (fragment == null || fragment.isEmpty()) {
            return List.of();
        }

        if (capturingProtocol) {
            protocolBuffer.append(fragment);
        } else {
            pendingText.append(fragment);
        }
        return drainAvailableContent();
    }

    /**
     * 完成当前流，释放普通文字尾部并返回恢复出的工具调用。
     *
     * <p>如果已经看见 DSML 开始标记但没有结束标记，则安全失败；协议原文不会作为
     * 普通文字释放，避免再次污染前端和会话历史。</p>
     *
     * @return 流结束时剩余的可见文字、工具调用和协议命中状态
     * @throws ProtocolException DSML 工具调用不完整时抛出
     */
    StreamCompletion complete() {
        if (completed) {
            throw new IllegalStateException("DSML 解析器不能重复完成");
        }

        List<String> visible = new ArrayList<>(drainAvailableContent());
        if (capturingProtocol) {
            throw new ProtocolException("模型返回了不完整的 DeepSeek DSML 工具调用");
        }
        if (!pendingText.isEmpty()) {
            visible.add(pendingText.toString());
            pendingText.setLength(0);
        }

        completed = true;
        return new StreamCompletion(List.copyOf(visible), List.copyOf(recoveredToolCalls), protocolDetected);
    }

    /**
     * 一次性解析非流式响应，供同步 LLM 调用复用同一协议规则。
     *
     * @param objectMapper JSON 解析器
     * @param text         模型返回的完整文字，可为空
     * @return 已移除 DSML 的可见文字和恢复出的工具调用
     */
    static ParseResult parseComplete(ObjectMapper objectMapper, String text) {
        if (text == null) {
            return new ParseResult(null, List.of(), false);
        }
        DeepSeekDsmlStreamParser parser = new DeepSeekDsmlStreamParser(objectMapper);
        List<String> visible = new ArrayList<>(parser.accept(text));
        StreamCompletion completion = parser.complete();
        visible.addAll(completion.visibleFragments());
        return new ParseResult(String.join("", visible), completion.toolCalls(), completion.protocolDetected());
    }

    /**
     * 持续排出当前能够确定的普通文字，并在 DSML 结束后解析工具调用。
     *
     * @return 本次新确认的可见文字片段
     */
    private List<String> drainAvailableContent() {
        List<String> visible = new ArrayList<>();

        while (true) {
            if (capturingProtocol) {
                EndMatch endMatch = findEarliestEndMarker(protocolBuffer);
                if (endMatch == null) {
                    if (protocolBuffer.length() > MAX_PROTOCOL_CHARS) {
                        throw new ProtocolException("DeepSeek DSML 工具调用超过允许的最大长度");
                    }
                    break;
                }

                int endExclusive = endMatch.index() + endMatch.marker().length();
                String protocol = protocolBuffer.substring(0, endExclusive);
                String tail = protocolBuffer.substring(endExclusive);
                recoveredToolCalls.addAll(parseProtocolBlock(protocol));

                protocolBuffer.setLength(0);
                capturingProtocol = false;
                if (!tail.isEmpty()) {
                    pendingText.append(tail);
                }
                continue;
            }

            StartMatch startMatch = findEarliestStartMarker(pendingText);
            if (startMatch != null) {
                if (startMatch.index() > 0) {
                    visible.add(pendingText.substring(0, startMatch.index()));
                }
                protocolBuffer.append(pendingText.substring(startMatch.index()));
                pendingText.setLength(0);
                capturingProtocol = true;
                protocolDetected = true;
                continue;
            }

            int heldSuffixLength = longestPossibleMarkerPrefixSuffix(pendingText);
            int visibleLength = pendingText.length() - heldSuffixLength;
            if (visibleLength > 0) {
                visible.add(pendingText.substring(0, visibleLength));
                pendingText.delete(0, visibleLength);
            }
            break;
        }

        return visible;
    }

    /**
     * 查找当前普通文字中最早出现的受支持 DSML 开始标记。
     *
     * @param text 待检查文字
     * @return 最早标记及位置；没有时返回 null
     */
    private StartMatch findEarliestStartMarker(CharSequence text) {
        StartMatch earliest = null;
        String value = text.toString();
        for (MarkerPair pair : MARKER_PAIRS) {
            int index = value.indexOf(pair.start());
            if (index >= 0 && (earliest == null || index < earliest.index())) {
                earliest = new StartMatch(index, pair.start());
            }
        }
        return earliest;
    }

    /**
     * 查找当前协议缓存中最早出现的受支持结束标记。
     *
     * @param text DSML 协议缓存
     * @return 最早结束标记及位置；没有时返回 null
     */
    private EndMatch findEarliestEndMarker(CharSequence text) {
        EndMatch earliest = null;
        String value = text.toString();
        for (MarkerPair pair : MARKER_PAIRS) {
            int index = value.indexOf(pair.end());
            if (index >= 0 && (earliest == null || index < earliest.index())) {
                earliest = new EndMatch(index, pair.end());
            }
        }
        return earliest;
    }

    /**
     * 计算末尾有多少字符仍可能组成下一个 DSML 开始标记。
     *
     * <p>只暂存这个最短必要后缀，避免为了识别跨 chunk 标记而延迟普通思考文字。</p>
     *
     * @param text 当前普通文字缓存
     * @return 需要继续等待下一 chunk 的后缀长度
     */
    private int longestPossibleMarkerPrefixSuffix(CharSequence text) {
        int longest = 0;
        String value = text.toString();
        for (MarkerPair pair : MARKER_PAIRS) {
            String marker = pair.start();
            int maxLength = Math.min(value.length(), marker.length() - 1);
            for (int length = maxLength; length > longest; length--) {
                if (value.regionMatches(value.length() - length, marker, 0, length)) {
                    longest = length;
                    break;
                }
            }
        }
        return longest;
    }

    /**
     * 解析一个完整 DSML 工具调用块。
     *
     * @param protocol 完整协议文字
     * @return 按模型顺序恢复出的工具调用
     * @throws ProtocolException 标签、属性或参数 JSON 不合法时抛出
     */
    private List<LlmToolCall> parseProtocolBlock(String protocol) {
        String normalized = normalizeProtocol(protocol).trim();
        String start = "<DSML_tool_calls>";
        String end = "</DSML_tool_calls>";
        if (!normalized.startsWith(start) || !normalized.endsWith(end)) {
            throw new ProtocolException("DeepSeek DSML 工具调用缺少合法的外层标签");
        }

        String body = normalized.substring(start.length(), normalized.length() - end.length());
        Matcher invokeMatcher = INVOKE_PATTERN.matcher(body);
        List<LlmToolCall> calls = new ArrayList<>();
        int cursor = 0;
        while (invokeMatcher.find()) {
            requireBlank(body.substring(cursor, invokeMatcher.start()), "invoke 标签之间包含未知内容");
            Map<String, String> invokeAttributes = parseAttributes(invokeMatcher.group(1));
            String toolName = invokeAttributes.get("name");
            if (toolName == null || toolName.isBlank()) {
                throw new ProtocolException("DeepSeek DSML invoke 缺少工具名称");
            }

            Map<String, Object> arguments = parseParameters(invokeMatcher.group(2));
            calls.add(new LlmToolCall(null, toolName, arguments));
            cursor = invokeMatcher.end();
        }

        requireBlank(body.substring(cursor), "tool_calls 标签内包含未知内容");
        if (calls.isEmpty()) {
            throw new ProtocolException("DeepSeek DSML tool_calls 中没有 invoke");
        }
        return calls;
    }

    /**
     * 解析单个 invoke 中的参数并恢复原始 JSON 类型。
     *
     * @param body invoke 标签正文
     * @return 保持参数顺序的参数 Map
     */
    private Map<String, Object> parseParameters(String body) {
        Matcher parameterMatcher = PARAMETER_PATTERN.matcher(body);
        Map<String, Object> arguments = new LinkedHashMap<>();
        int cursor = 0;
        while (parameterMatcher.find()) {
            requireBlank(body.substring(cursor, parameterMatcher.start()), "parameter 标签之间包含未知内容");
            Map<String, String> attributes = parseAttributes(parameterMatcher.group(1));
            String name = attributes.get("name");
            String stringFlag = attributes.get("string");
            if (name == null || name.isBlank()) {
                throw new ProtocolException("DeepSeek DSML parameter 缺少参数名称");
            }
            if (!"true".equals(stringFlag) && !"false".equals(stringFlag)) {
                throw new ProtocolException("DeepSeek DSML parameter 的 string 属性无效");
            }
            if (arguments.containsKey(name)) {
                throw new ProtocolException("DeepSeek DSML 出现重复参数名称");
            }

            String valueText = parameterMatcher.group(2);
            Object value = "true".equals(stringFlag) ? valueText : parseJsonValue(valueText);
            arguments.put(name, value);
            cursor = parameterMatcher.end();
        }

        requireBlank(body.substring(cursor), "invoke 标签内包含未知内容");
        return arguments;
    }

    /**
     * 解析 {@code string="false"} 参数的 JSON 值。
     *
     * @param valueText JSON 文本
     * @return 字符串以外的原始 JSON 值
     */
    private Object parseJsonValue(String valueText) {
        try {
            return objectMapper.readValue(valueText.trim(), Object.class);
        } catch (Exception e) {
            throw new ProtocolException("DeepSeek DSML 非字符串参数不是合法 JSON", e);
        }
    }

    /**
     * 解析标签属性，并拒绝属性之间无法识别的残留文字。
     *
     * @param attributesText 标签属性部分
     * @return 属性名到属性值的映射
     */
    private Map<String, String> parseAttributes(String attributesText) {
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(attributesText);
        Map<String, String> attributes = new LinkedHashMap<>();
        int cursor = 0;
        while (matcher.find()) {
            requireBlank(attributesText.substring(cursor, matcher.start()), "DSML 标签包含非法属性");
            String previous = attributes.putIfAbsent(matcher.group(1), matcher.group(2));
            if (previous != null) {
                throw new ProtocolException("DSML 标签包含重复属性");
            }
            cursor = matcher.end();
        }
        requireBlank(attributesText.substring(cursor), "DSML 标签包含非法属性");
        return attributes;
    }

    /**
     * 把官方标记和线上观察到的兼容变体统一成内部标签名。
     *
     * @param protocol 原始 DSML
     * @return 仅供本解析器使用的统一标签文本
     */
    private String normalizeProtocol(String protocol) {
        String normalized = protocol;
        for (String token : DSML_TOKEN_VARIANTS) {
            normalized = normalized.replace("</" + token, "</DSML_");
            normalized = normalized.replace("<" + token, "<DSML_");
        }
        return normalized;
    }

    /**
     * 要求协议标签之间只能有空白文字。
     *
     * @param text    待校验文字
     * @param message 校验失败时的安全错误信息
     */
    private void requireBlank(String text, String message) {
        if (!text.isBlank()) {
            throw new ProtocolException(message);
        }
    }

    /** DSML 开始与结束标记组合。 */
    private record MarkerPair(String start, String end) {
    }

    /** DSML 开始标记的位置。 */
    private record StartMatch(int index, String marker) {
    }

    /** DSML 结束标记的位置。 */
    private record EndMatch(int index, String marker) {
    }

    /**
     * 流结束结果。
     *
     * @param visibleFragments 流结束时仍需展示的普通文字
     * @param toolCalls        从 DSML 恢复出的工具调用
     * @param protocolDetected 是否命中过 DSML 协议
     */
    record StreamCompletion(List<String> visibleFragments,
                            List<LlmToolCall> toolCalls,
                            boolean protocolDetected) {
    }

    /**
     * 非流式完整解析结果。
     *
     * @param visibleText      已移除 DSML 的普通文字
     * @param toolCalls        从 DSML 恢复出的工具调用
     * @param protocolDetected 是否命中过 DSML 协议
     */
    record ParseResult(String visibleText,
                       List<LlmToolCall> toolCalls,
                       boolean protocolDetected) {
    }

    /**
     * DSML 协议异常。
     *
     * <p>异常信息只描述结构问题，不携带模型返回的协议原文，避免工具参数进入错误页面或日志。</p>
     */
    static final class ProtocolException extends RuntimeException {

        /**
         * 创建不带底层异常的协议异常。
         *
         * @param message 安全错误信息
         */
        ProtocolException(String message) {
            super(message);
        }

        /**
         * 创建带底层解析异常的协议异常。
         *
         * @param message 安全错误信息
         * @param cause   底层异常
         */
        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
