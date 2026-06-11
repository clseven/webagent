package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.*;
import com.example.sandbox.web.service.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 服务抽象基类 — 提供 OpenAI 兼容 API 的通用实现。
 *
 * <h3>设计目的</h3>
 * <p>不同 LLM 厂商（DeepSeek、智谱等）的 API 都遵循 OpenAI 兼容协议，
 * 大量逻辑（请求构建、ReAct 解析、Token 统计、异常处理）可以复用。
 * 子类只需配置 apiUrl、apiKey、model 即可。</p>
 *
 * <h3>支持的调用方式</h3>
 * <ul>
 *   <li>{@link #chat} — 简单聊天</li>
 *   <li>{@link #chatWithSystem} — 带系统提示的聊天</li>
 *   <li>{@link #chatWithSystemResponse} — 返回完整响应（包含 Token 用量）</li>
 *   <li>{@link #chatWithTools} — 带工具的聊天（ReAct 模式）</li>
 * </ul>
 *
 * <h3>工具调用解析策略</h3>
 * <ol>
 *   <li>优先解析 LLM 原生 tool_calls 字段（OpenAI 标准）</li>
 *   <li>回退到 ReAct 文本解析（兼容老模型）</li>
 * </ol>
 *
 * @author example
 * @date 2026/05/26
 */
public abstract class BaseLlmServiceImpl implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(BaseLlmServiceImpl.class);

    /** LLM API 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** LLM API 响应超时（秒）— 长任务需要较长时间 */
    private static final int RESPONSE_TIMEOUT_SECONDS = 300;

    /** ReAct 文本格式解析：匹配 "Action: tool_name" */
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);

    /** ReAct 文本格式解析：匹配 "Action Input:" */
    private static final Pattern INPUT_PATTERN = Pattern.compile("Action\\s*Input:\\s*", Pattern.CASE_INSENSITIVE);

    /** 参数解析：匹配 "key=value" 格式 */
    private static final Pattern KV_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(.*)");

    /** 工具名称合法性校验（防止 LLM 输出非法字符） */
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** WebClient 实例（复用 HTTP 连接） */
    private final WebClient webClient;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /** 模型名称（如 deepseek-chat、glm-4） */
    private final String model;

    /**
     * 构造 LLM 服务实例
     *
     * @param apiUrl       LLM API 地址（如 https://api.deepseek.com）
     * @param apiKey       API 密钥
     * @param model        模型名称
     * @param objectMapper JSON 序列化工具
     */
    protected BaseLlmServiceImpl(String apiUrl, String apiKey, String model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS));

        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        log.info("Initialized {} with model: {}", getClass().getSimpleName(), model);
    }

    /**
     * 简单聊天（无系统提示，调用 chatWithSystem 复用逻辑）
     */
    @Override
    public String chat(List<ChatMessage> messages) {
        return chatWithSystem("", messages);
    }

    /**
     * 带系统提示的聊天
     *
     * <p>异常时返回友好的错误消息，不抛异常，确保 Agent 流程不会中断。</p>
     */
    @Override
    public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
        try {
            LlmRequest request = buildLlmRequest(systemPrompt, messages, null);
            logRequest(messages.size(), 0);

            String responseJson = callLlm(request);
            LlmCompletionResponse completion = LlmCompletionResponse.parse(objectMapper, responseJson);
            logTokenUsage(completion.getUsage());

            LlmMessage assistantMsg = completion.firstChoice() != null ? completion.firstChoice().message() : null;
            return assistantMsg != null ? assistantMsg.getContent() : "";

        } catch (Exception e) {
            log.error("LLM call failed", e);
            return "抱歉，AI 服务暂时不可用，请稍后重试。";
        }
    }

    /**
     * 带系统提示的聊天（返回完整响应，包含 Token 用量和思考链）
     */
    @Override
    public LlmResponse chatWithSystemResponse(String systemPrompt, List<ChatMessage> messages) {
        try {
            LlmRequest request = buildLlmRequest(systemPrompt, messages, null);
            logRequest(messages.size(), 0);

            String responseJson = callLlm(request);
            LlmCompletionResponse completion = LlmCompletionResponse.parse(objectMapper, responseJson);
            logTokenUsage(completion.getUsage());

            LlmMessage assistantMsg = completion.firstChoice() != null ? completion.firstChoice().message() : null;
            String content = assistantMsg != null ? assistantMsg.getContent() : "";
            String reasoning = assistantMsg != null ? assistantMsg.getReasoningContent() : null;
            return LlmResponse.text(content, reasoning, completion.getUsage());

        } catch (Exception e) {
            log.error("LLM call failed", e);
            return LlmResponse.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 带工具的聊天 — ReAct 模式核心调用
     *
     * <h3>解析流程</h3>
     * <ol>
     *   <li>调用 LLM API</li>
     *   <li>优先解析原生 tool_calls（OpenAI 标准格式）</li>
     *   <li>如果 LLM 没有返回 tool_calls，尝试 ReAct 文本格式解析</li>
     *   <li>都没有则作为普通文本返回</li>
     * </ol>
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表
     * @param tools        可用工具定义
     * @return LLM 响应（可能包含工具调用）
     */
    @Override
    public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        try {
            LlmRequest request = buildLlmRequest(systemPrompt, messages, tools);
            logRequest(messages.size(), tools != null ? tools.size() : 0);

            String responseJson = callLlm(request);
            LlmCompletionResponse completion = LlmCompletionResponse.parse(objectMapper, responseJson);
            LlmUsage usage = completion.getUsage();

            LlmMessage assistantMsg = completion.firstChoice() != null ? completion.firstChoice().message() : null;
            String content = assistantMsg != null ? assistantMsg.getContent() : "";
            String reasoning = assistantMsg != null ? assistantMsg.getReasoningContent() : null;

            // 优先解析原生 tool_calls
            if (completion.hasToolCalls()) {
                LlmToolCall toolCall = assistantMsg.getToolCalls().get(0);
                if (isValidToolName(toolCall.name())) {
                    log.info("LLM 工具调用: {} 参数: {}", toolCall.name(), toolCall.arguments());
                    return LlmResponse.toolCall(toolCall, content, reasoning, usage);
                }
            }

            // 回退：ReAct 文本解析
            LlmResponse reactResponse = parseReActToolCall(content, reasoning, usage);
            if (reactResponse != null) {
                return reactResponse;
            }

            return LlmResponse.text(content, reasoning, usage);

        } catch (Exception e) {
            log.error("LLM call with tools failed", e);
            return LlmResponse.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    // ==================== 请求构建 ====================

    /**
     * 构建 LLM 请求体
     *
     * <p>将业务层的 ChatMessage 转换为 API 要求的 LlmMessage 格式。</p>
     */
    private LlmRequest buildLlmRequest(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        List<LlmMessage> llmMessages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            llmMessages.add(LlmMessage.system(systemPrompt));
        }

        for (ChatMessage msg : messages) {
            llmMessages.add(LlmMessage.builder()
                    .role(msg.getRole())
                    .content(msg.getContent())
                    .reasoningContent(msg.getReasoning())
                    .toolCalls(msg.getToolCalls())
                    .toolCallId(msg.getToolCallId())
                    .build());
        }

        return LlmRequest.builder()
                .model(model)
                .messages(llmMessages)
                .tools(tools)
                .build();
    }

    /**
     * 发送 HTTP 请求到 LLM API
     *
     * <p>使用 WebClient 调用 OpenAI 兼容的 /chat/completions 端点。</p>
     */
    private String callLlm(LlmRequest request) {
        Map<String, Object> requestBody = request.toApiFormat();
        logRequestBody(requestBody);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(responseBody -> {
                            int status = response.statusCode().value();
                            if (response.statusCode().is2xxSuccessful()) {
                                logResponseSummary(status, responseBody);
                                return Mono.just(responseBody);
                            }

                            log.error("【LLM 错误响应】status={} body={}", status, responseBody);
                            return Mono.error(new IllegalStateException(
                                    "LLM API returned HTTP " + status + ": " + responseBody));
                        }))
                .block();
    }

    // ==================== ReAct 文本解析（回退机制） ====================

    /**
     * 解析 ReAct 文本格式的工具调用（兼容老模型）
     *
     * <p>支持的格式示例：</p>
     * <pre>
     * Thought: 我需要读取文件
     * Action: read_file
     * Action Input: {"path": "/config.txt"}
     * </pre>
     *
     * <p>如果解析失败返回 null，调用方会按普通文本处理。</p>
     */
    private LlmResponse parseReActToolCall(String content, String reasoning, LlmUsage usage) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        String thinking = null;
        int actionIndex = content.toLowerCase().indexOf("action:");
        if (actionIndex > 0) {
            thinking = content.substring(0, actionIndex).trim();
        } else {
            thinking = content;
        }

        Matcher actionMatcher = ACTION_PATTERN.matcher(content);
        if (!actionMatcher.find()) {
            return null;
        }

        String toolName = actionMatcher.group(1).trim();

        Matcher inputMatcher = INPUT_PATTERN.matcher(content);
        if (!inputMatcher.find()) {
            return null;
        }

        int inputStart = inputMatcher.end();
        String remaining = content.substring(inputStart);

        Map<String, Object> arguments = null;

        if (remaining.trim().startsWith("{")) {
            int braceStart = remaining.indexOf('{');
            String inputContent = extractBalancedBraces(remaining, braceStart);
            if (inputContent != null) {
                arguments = parseJsonOrKeyValue(inputContent);
            }
        }

        if (arguments == null) {
            arguments = parseKeyValueLines(remaining);
        }

        if (arguments != null && !arguments.isEmpty()) {
            log.debug("解析 ReAct 工具调用: {} 参数: {} 思考: {}", toolName, arguments, thinking);
            LlmToolCall toolCall = new LlmToolCall(null, toolName, arguments);
            return LlmResponse.toolCall(toolCall, thinking, reasoning, usage);
        }

        log.warn("Failed to parse Action Input for tool: {}", toolName);
        return null;
    }

    // ==================== 参数解析 ====================

    /**
     * 解析参数：优先尝试 JSON，回退到 key=value 格式
     */
    private Map<String, Object> parseJsonOrKeyValue(String content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue("{" + content + "}", Map.class);
            return result;
        } catch (Exception e) {
            log.debug("JSON 格式无效，尝试 key=value 格式");
        }

        Map<String, Object> arguments = new java.util.HashMap<>();
        Matcher kvMatcher = KV_PATTERN.matcher(content);

        int lastEnd = 0;
        String lastKey = null;

        while (kvMatcher.find()) {
            if (lastKey != null) {
                String value = content.substring(lastEnd, kvMatcher.start()).trim();
                value = cleanValue(value);
                arguments.put(lastKey, value);
            }
            lastKey = kvMatcher.group(1);
            lastEnd = kvMatcher.end();
        }

        if (lastKey != null && lastEnd < content.length()) {
            String value = content.substring(lastEnd).trim();
            value = cleanValue(value);
            arguments.put(lastKey, value);
        }

        return arguments.isEmpty() ? null : arguments;
    }

    /**
     * 逐行解析 key=value 格式的参数
     */
    private Map<String, Object> parseKeyValueLines(String content) {
        Map<String, Object> arguments = new java.util.HashMap<>();
        String[] lines = content.split("\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = KV_PATTERN.matcher(line);
            if (matcher.matches()) {
                arguments.put(matcher.group(1), matcher.group(2).trim());
            }
        }

        return arguments.isEmpty() ? null : arguments;
    }

    // ==================== 工具方法 ====================

    /**
     * 校验工具名称合法性（防止 LLM 注入非法字符）
     */
    private boolean isValidToolName(String toolName) {
        return toolName != null && TOOL_NAME_PATTERN.matcher(toolName).matches();
    }

    /**
     * 清理参数值（去除尾部逗号、空格等）
     */
    private String cleanValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        value = value.trim();
        if (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 提取平衡的大括号内容（处理嵌套的 JSON）
     */
    private String extractBalancedBraces(String content, int start) {
        int depth = 0;
        int end = start;

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }

        if (depth != 0) {
            return null;
        }
        return content.substring(start + 1, end);
    }

    // ==================== Token 追踪 ====================

    /**
     * 记录 Token 用量到日志
     */
    private void logTokenUsage(LlmUsage usage) {
        if (usage != null) {
            log.info("Token 消耗: prompt={}, completion={}, total={}, cacheHit={}",
                    usage.promptTokens(), usage.completionTokens(),
                    usage.totalTokens(), usage.cacheHitTokens());
        }
    }

    @SuppressWarnings("unchecked")
    private void logResponseSummary(int status, String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");

            String finishReason = "?";
            String content = "";
            String toolInfo = "";

            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                finishReason = (String) choice.getOrDefault("finish_reason", "?");

                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null) {
                    String c = (String) message.get("content");
                    if (c != null && !c.isEmpty()) {
                        content = c.length() > 100 ? c.substring(0, 100) + "..." : c;
                    }
                    List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        Map<String, Object> fn = (Map<String, Object>) toolCalls.get(0).get("function");
                        if (fn != null) {
                            toolInfo = " tool=" + fn.getOrDefault("name", "?");
                        }
                    }
                }
            }

            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            String usageInfo = "";
            if (usage != null) {
                usageInfo = String.format(" usage={prompt=%s,completion=%s,cacheHit=%s,total=%s}",
                        getAsInt(usage, "prompt_tokens"), getAsInt(usage, "completion_tokens"),
                        getAsInt(usage, "prompt_cache_hit_tokens"), getAsInt(usage, "total_tokens"));
            }

            log.info("【LLM 响应】status={} finish={}{}{}{}",
                    status, finishReason, toolInfo,
                    !content.isEmpty() ? " content=\"" + content + "\"" : "",
                    usageInfo);
        } catch (Exception e) {
            log.debug("【LLM 响应】摘要解析失败, status={}", status);
        }
    }

    /**
     * 记录请求信息到日志
     */
    private void logRequest(int messageCount, int toolCount) {
        if (toolCount > 0) {
            log.info("【LLM 请求】messages: {} tools: {}", messageCount, toolCount);
        } else {
            log.info("【LLM 请求】messages: {}", messageCount);
        }
    }

    @SuppressWarnings("unchecked")
    private void logRequestBody(Map<String, Object> requestBody) {
        try {
            String model = (String) requestBody.getOrDefault("model", "?");
            List<Map<String, Object>> messages = (List<Map<String, Object>>) requestBody.get("messages");
            List<?> tools = (List<?>) requestBody.get("tools");
            int msgCount = messages != null ? messages.size() : 0;
            int toolCount = tools != null ? tools.size() : 0;

            // 取最后一条消息的 role + 内容摘要
            String latestInfo = "";
            if (messages != null && !messages.isEmpty()) {
                Map<String, Object> last = messages.get(messages.size() - 1);
                String role = (String) last.getOrDefault("role", "?");
                String content = (String) last.getOrDefault("content", "");
                // tool_calls 消息没有 content，取 tool_calls 信息
                if (content == null || content.isEmpty()) {
                    List<Map<String, Object>> tcList = (List<Map<String, Object>>) last.get("tool_calls");
                    if (tcList != null && !tcList.isEmpty()) {
                        String tcName = "?";
                        Map<String, Object> fn = (Map<String, Object>) tcList.get(0).get("function");
                        if (fn != null) {
                            tcName = (String) fn.getOrDefault("name", "?");
                        }
                        content = "[tool_call: " + tcName + "]";
                    } else {
                        content = "(empty)";
                    }
                }
                // 截断过长内容
                if (content.length() > 100) {
                    content = content.substring(0, 100) + "...";
                }
                latestInfo = " latest=" + role + "(\"" + content + "\")";
            }

            log.info("【LLM 请求】model={} messages={} tools={}{}", model, msgCount, toolCount, latestInfo);
        } catch (Exception e) {
            log.warn("【LLM 请求】摘要生成失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Flux<String> callLlmStream(Map<String, Object> requestBody) {
        logRequestBody(requestBody);

        // 流式响应累积器：收集关键信息，完成后统一输出
        StringBuilder accContent = new StringBuilder();
        StringBuilder accReasoning = new StringBuilder();
        String[] accToolName = {null};
        StringBuilder accToolArgs = new StringBuilder();
        int[] accUsage = {0, 0, 0, 0}; // prompt, completion, total, cacheHit
        int[] chunkCount = {0};
        String[] finishReason = {null};

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .exchangeToFlux(response -> {
                    int status = response.statusCode().value();
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(String.class)
                                .doOnNext(chunk -> {
                                    chunkCount[0]++;
                                    parseStreamSummary(chunk, accContent, accReasoning,
                                            accToolName, accToolArgs, accUsage, finishReason);
                                })
                                .doAfterTerminate(() -> {
                                    String toolInfo = accToolName[0] != null
                                            ? " tool=" + accToolName[0]
                                              + (accToolArgs.length() > 0
                                                 ? " args=" + accToolArgs : "")
                                            : "";
                                    log.info("【LLM 流式响应】status={} finish={} chunks={}{}{} usage={prompt={},completion={},cacheHit={},total={}}",
                                            status,
                                            finishReason[0] != null ? finishReason[0] : "unknown",
                                            chunkCount[0],
                                            accContent.length() > 0 ? " content=\"" + accContent + "\"" : "",
                                            toolInfo,
                                            accUsage[0], accUsage[1], accUsage[3], accUsage[2]);
                                });
                    }

                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMapMany(responseBody -> {
                                log.error("【LLM 错误响应】status={} body={}", status, responseBody);
                                return Flux.error(new IllegalStateException(
                                        "LLM API returned HTTP " + status + ": " + responseBody));
                            });
                });
    }

    /**
     * 轻量解析流式 chunk，累积关键信息（仅用于日志汇总，不影响下游解析）
     */
    @SuppressWarnings("unchecked")
    private void parseStreamSummary(String chunk,
                                    StringBuilder accContent, StringBuilder accReasoning,
                                    String[] accToolName, StringBuilder accToolArgs,
                                    int[] accUsage, String[] finishReason) {
        String json = normalizeStreamJson(chunk);
        if (json == null) return;

        try {
            Map<String, Object> response = objectMapper.readValue(json, Map.class);

            // usage
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            if (usage != null) {
                accUsage[0] = getAsInt(usage, "prompt_tokens");
                accUsage[1] = getAsInt(usage, "completion_tokens");
                accUsage[2] = getAsInt(usage, "total_tokens");
                accUsage[3] = getAsInt(usage, "prompt_cache_hit_tokens");
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return;

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) return;

            // content
            String content = (String) delta.get("content");
            if (content != null && !content.isEmpty()) {
                accContent.append(content);
            }

            // reasoning_content
            String reasoning = (String) delta.get("reasoning_content");
            if (reasoning != null && !reasoning.isEmpty()) {
                accReasoning.append(reasoning);
            }

            // tool_calls
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (Map<String, Object> tc : toolCalls) {
                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                    if (function != null) {
                        String name = (String) function.get("name");
                        String args = (String) function.get("arguments");
                        if (name != null && !name.isBlank()) {
                            accToolName[0] = name;
                        }
                        if (args != null && !args.isBlank()) {
                            accToolArgs.append(args);
                        }
                    }
                }
            }

            // finish_reason
            String fr = (String) choice.get("finish_reason");
            if (fr != null) {
                finishReason[0] = fr;
            }

        } catch (Exception e) {
            // 仅日志汇总用，解析失败静默忽略
        }
    }

    // ==================== 流式调用实现 ====================

    /**
     * 流式聊天（不带工具）
     *
     * <p>使用 SSE 协议实时返回 LLM 生成的 token。</p>
     */
    @Override
    public Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages) {
        return Flux.create(emitter -> {
            try {
                LlmRequest request = buildLlmRequest(systemPrompt, messages, null);
                Map<String, Object> apiFormat = request.toApiFormat();
                // 添加 stream: true
                if (apiFormat instanceof java.util.HashMap) {
                    ((java.util.HashMap<String, Object>) apiFormat).put("stream", true);
                }

                logRequest(messages.size(), 0);

                callLlmStream(apiFormat)
                    .subscribe(
                        chunk -> {
                            if (emitter.isCancelled()) return;

                            String token = parseTokenFromStreamChunk(chunk);
                            if (token != null) {
                                emitter.next(token);
                            }
                        },
                        error -> {
                            log.error("LLM 流式调用失败", error);
                            if (!emitter.isCancelled()) {
                                emitter.error(error);
                            }
                        },
                        () -> {
                            if (!emitter.isCancelled()) {
                                emitter.complete();
                            }
                        }
                    );

            } catch (Exception e) {
                log.error("LLM 流式调用失败", e);
                emitter.error(e);
            }
        });
    }

    /**
     * 流式聊天（带工具）— ReAct 流式模式核心
     *
     * <p>解析 OpenAI 格式的 SSE 流，支持：</p>
     * <ul>
     *   <li>token — LLM 输出的 token</li>
     *   <li>reasoning — 思考链 token</li>
     *   <li>tool_call — 工具调用（流式累积）</li>
     *   <li>finish — 流结束</li>
     * </ul>
     */
    @Override
    public Flux<LlmStreamChunk> chatWithToolsStream(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        return Flux.create(emitter -> {
            try {
                LlmRequest request = buildLlmRequest(systemPrompt, messages, tools);
                Map<String, Object> apiFormat = request.toApiFormat();
                if (apiFormat instanceof java.util.HashMap) {
                    ((java.util.HashMap<String, Object>) apiFormat).put("stream", true);
                }

                logRequest(messages.size(), tools != null ? tools.size() : 0);

                // 累积工具调用参数（流式中可能跨多个 chunk）
                AtomicReference<LlmToolCall.Builder> toolCallBuilder = new AtomicReference<>();
                AtomicReference<LlmUsage> usageRef = new AtomicReference<>();

                callLlmStream(apiFormat)
                    .subscribe(
                        chunk -> {
                            if (emitter.isCancelled()) return;

                            List<LlmStreamChunk> chunks = parseStreamChunkWithTools(chunk, toolCallBuilder, usageRef);
                            for (LlmStreamChunk c : chunks) {
                                emitter.next(c);
                            }
                        },
                        error -> {
                            log.error("LLM 流式调用失败", error);
                            if (!emitter.isCancelled()) {
                                emitter.error(error);
                            }
                        },
                        () -> {
                            if (!emitter.isCancelled()) {
                                // 如果有累积的工具调用，发出 tool_call 事件
                                LlmToolCall.Builder builder = toolCallBuilder.get();
                                if (builder != null && builder.getName() != null) {
                                    emitter.next(LlmStreamChunk.toolCall(builder.build()));
                                }
                                // 发出 finish 事件
                                emitter.next(LlmStreamChunk.finish(usageRef.get()));
                                emitter.complete();
                            }
                        }
                    );

            } catch (Exception e) {
                log.error("LLM 流式调用失败", e);
                emitter.error(e);
            }
        });
    }

    /**
     * 解析流式响应中的 token
     */
    private String parseTokenFromStreamChunk(String chunk) {
        String json = normalizeStreamJson(chunk);
        if (json == null) return null;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(json, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return null;

            @SuppressWarnings("unchecked")
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) return null;

            String content = (String) delta.get("content");
            return content != null && !content.isEmpty() ? content : null;

        } catch (Exception e) {
            log.debug("解析流式 token 失败: {}", chunk);
            return null;
        }
    }

    private String normalizeStreamJson(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }

        String text = chunk.trim();
        if (text.startsWith("data:")) {
            text = text.substring(5).trim();
        }

        if ("[DONE]".equals(text) || !text.startsWith("{")) {
            return null;
        }
        return text;
    }

    /**
     * 解析流式响应（带工具支持）
     *
     * <p>OpenAI 流式格式：</p>
     * <pre>
     * data: {"choices":[{"delta":{"content":"xxx"}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"function":{"name":"read_file","arguments":"..."}}]}}]}
     * data: [DONE]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private List<LlmStreamChunk> parseStreamChunkWithTools(String chunk,
                                                            AtomicReference<LlmToolCall.Builder> toolCallBuilder,
                                                            AtomicReference<LlmUsage> usageRef) {
        List<LlmStreamChunk> result = new ArrayList<>();

        String json = normalizeStreamJson(chunk);
        if (json == null) return result;

        try {
            Map<String, Object> response = objectMapper.readValue(json, Map.class);

            // usage 可能位于 choices 为空的最后一个 chunk，需优先处理
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            if (usage != null) {
                usageRef.set(LlmUsage.of(
                    getAsInt(usage, "prompt_tokens"),
                    getAsInt(usage, "completion_tokens"),
                    getAsInt(usage, "total_tokens"),
                    getAsInt(usage, "prompt_cache_hit_tokens")
                ));
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return result;

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) return result;

            // 1. 处理普通 content token
            String content = (String) delta.get("content");
            if (content != null && !content.isEmpty()) {
                result.add(LlmStreamChunk.token(content));
            }

            // 2. 处理思考链 token（reasoning_content）
            String reasoning = (String) delta.get("reasoning_content");
            if (reasoning != null && !reasoning.isEmpty()) {
                result.add(LlmStreamChunk.reasoning(reasoning));
            }

            // 3. 处理工具调用（流式累积）
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (Map<String, Object> tc : toolCalls) {
                    // 初始化或获取 builder
                    LlmToolCall.Builder builder = toolCallBuilder.get();
                    if (builder == null) {
                        builder = LlmToolCall.builder();
                        toolCallBuilder.set(builder);
                    }

                    String id = (String) tc.get("id");
                    if (id != null && !id.isBlank()) {
                        builder.id(id);
                    }

                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                    if (function == null) continue;

                    // 累积 name
                    String name = (String) function.get("name");
                    if (name != null && !name.isBlank()) {
                        builder.name(name);
                    }

                    // 累积 arguments（JSON 字符串可能跨多个 chunk）
                    String args = (String) function.get("arguments");
                    if (args != null) {
                        builder.appendArguments(args);
                    }
                }
            }

            // 4. 检查 finish_reason
            String finishReason = (String) choice.get("finish_reason");
            if ("tool_calls".equals(finishReason)) {
                // 工具调用完成，发出 tool_call 事件
                LlmToolCall.Builder builder = toolCallBuilder.get();
                if (builder != null && builder.getName() != null) {
                    result.add(LlmStreamChunk.toolCall(builder.build()));
                    toolCallBuilder.set(null); // 重置
                }
            }

        } catch (Exception e) {
            log.debug("解析流式响应失败: {}", chunk, e);
        }

        return result;
    }

    private int getAsInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
