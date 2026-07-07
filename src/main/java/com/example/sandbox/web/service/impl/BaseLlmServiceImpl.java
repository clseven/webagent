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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li>优先解析 LLM 原生 tool_calls 字段，收集全部 tool_call 到列表供并发调度（OpenAI 标准）</li>
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

    /** 厂商错误策略；默认策略不改变现有 LLM 行为。 */
    private final LlmErrorPolicy errorPolicy;

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
        this(apiUrl, apiKey, model, objectMapper, LlmErrorPolicy.DEFAULT);
    }

    /**
     * 构造带厂商错误策略的 LLM 服务实例。
     *
     * @param apiUrl       LLM API 地址
     * @param apiKey       API 密钥
     * @param model        模型名称
     * @param objectMapper JSON 序列化工具
     * @param errorPolicy  厂商错误分类、重试和提示策略
     */
    protected BaseLlmServiceImpl(String apiUrl, String apiKey, String model, ObjectMapper objectMapper,
                                 LlmErrorPolicy errorPolicy) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.errorPolicy = errorPolicy;

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
            validateFinishReason(completion);
            logTokenUsage(completion.getUsage());

            LlmMessage assistantMsg = completion.firstChoice() != null ? completion.firstChoice().message() : null;
            return assistantMsg != null ? assistantMsg.getContent() : "";

        } catch (Exception e) {
            log.error("LLM call failed", e);
            propagateIfConfigured(e);
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
            validateFinishReason(completion);
            logTokenUsage(completion.getUsage());

            LlmMessage assistantMsg = completion.firstChoice() != null ? completion.firstChoice().message() : null;
            String content = assistantMsg != null ? assistantMsg.getContent() : "";
            String reasoning = assistantMsg != null ? assistantMsg.getReasoningContent() : null;
            return LlmResponse.text(content, reasoning, completion.getUsage());

        } catch (Exception e) {
            log.error("LLM call failed", e);
            propagateIfConfigured(e);
            return LlmResponse.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 带工具的聊天 — ReAct 模式核心调用
     *
     * <h3>解析流程</h3>
     * <ol>
     *   <li>调用 LLM API</li>
     *   <li>优先解析原生 tool_calls，收集全部合法 tool_call 到列表（一轮可能多个，供并发调度）</li>
     *   <li>如果 LLM 没有返回 tool_calls，尝试 ReAct 文本格式解析</li>
     *   <li>都没有则作为普通文本返回</li>
     * </ol>
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表
     * @param tools        可用工具定义
     * @return LLM 响应（可能包含一个或多个工具调用）
     */
    @Override
    public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        try {
            LlmRequest request = buildLlmRequest(systemPrompt, messages, tools);
            logRequest(messages.size(), tools != null ? tools.size() : 0);

            String responseJson = callLlm(request);
            LlmCompletionResponse completion = LlmCompletionResponse.parse(objectMapper, responseJson);
            validateFinishReason(completion);
            LlmUsage usage = completion.getUsage();

            LlmMessage assistantMsg = completion.firstChoice() != null ? completion.firstChoice().message() : null;
            String content = assistantMsg != null ? assistantMsg.getContent() : "";
            String reasoning = assistantMsg != null ? assistantMsg.getReasoningContent() : null;

            // 优先解析原生 tool_calls（一轮可能有多个，全部接出供并发调度）
            if (completion.hasToolCalls()) {
                List<LlmToolCall> validCalls = new ArrayList<>();
                for (LlmToolCall tc : assistantMsg.getToolCalls()) {
                    if (isValidToolName(tc.name())) {
                        validCalls.add(tc);
                    }
                }
                if (!validCalls.isEmpty()) {
                    if (validCalls.size() == 1) {
                        log.info("LLM 工具调用: {} 参数: {}", validCalls.get(0).name(), validCalls.get(0).arguments());
                    } else {
                        log.info("LLM 并发工具调用: {} 个 {}", validCalls.size(),
                                validCalls.stream().map(LlmToolCall::name).toList());
                    }
                    return LlmResponse.toolCalls(validCalls, content, reasoning, usage);
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
            propagateIfConfigured(e);
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
                    .contentParts(msg.getContentParts())
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
        customizeRequestBody(requestBody);
        logRequestBody(requestBody);

        Mono<String> requestMono = webClient.post()
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
                            return Mono.error(errorPolicy.httpError(status, responseBody));
                        }));

        if (errorPolicy.maxRetries() > 0) {
            requestMono = requestMono.retryWhen(buildRetrySpec(() -> true));
        }

        return requestMono
                .onErrorMap(errorPolicy::normalize)
                .block();
    }

    /**
     * 允许具体厂商在发送前扩展请求体。
     *
     * <p>默认实现不修改请求体，子类可添加仅由对应厂商支持的字段，
     * 避免将专有协议参数传给其他 OpenAI 兼容服务。</p>
     *
     * @param requestBody 即将发送给模型服务的请求体，可原地修改
     */
    protected void customizeRequestBody(Map<String, Object> requestBody) {
        // 默认不添加厂商专有参数。
    }

    /**
     * 按厂商策略决定是否将同步调用异常继续抛给上层。
     *
     * <p>默认策略保持历史兜底文本；DeepSeek 策略则抛出明确错误，
     * 由 Agent 的 SSE 错误事件展示给用户。</p>
     *
     * @param error 当前调用异常
     */
    private void propagateIfConfigured(Exception error) {
        if (errorPolicy.propagateErrors()) {
            throw errorPolicy.normalize(error);
        }
    }

    /**
     * 校验非流式响应的结束原因。
     *
     * @param completion 已解析的模型响应
     */
    private void validateFinishReason(LlmCompletionResponse completion) {
        LlmChoice choice = completion.firstChoice();
        if (choice == null) {
            return;
        }
        RuntimeException error = errorPolicy.finishReasonError(choice.finishReason());
        if (error != null) {
            throw error;
        }
    }

    /**
     * 构建指数退避重试规则。
     *
     * <p>首次重试约等待 1 秒，第二次约等待 2 秒，并加入 20% 随机抖动。
     * {@code retryAllowed} 用于流式调用：一旦已经输出内容，就禁止重试，
     * 避免重复文字或重复工具调用。</p>
     *
     * @param retryAllowed 当前调用阶段是否仍允许重试
     * @return Reactor 重试规则
     */
    private Retry buildRetrySpec(java.util.function.BooleanSupplier retryAllowed) {
        return Retry.backoff(errorPolicy.maxRetries(), Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(2))
                .jitter(0.2)
                .filter(error -> retryAllowed.getAsBoolean() && errorPolicy.isRetryable(error))
                .doBeforeRetry(signal -> log.warn(
                        "LLM 临时故障，准备第 {} 次重试: {}",
                        signal.totalRetries() + 1, signal.failure().getMessage()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
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
            log.debug("【LLM 请求准备】businessMessages={} tools={}", messageCount, toolCount);
        } else {
            log.debug("【LLM 请求准备】businessMessages={}", messageCount);
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

            String latestInfo = "";
            if (messages != null && !messages.isEmpty()) {
                latestInfo = buildLatestMessageLogInfo(messages.get(messages.size() - 1));
            }

            log.info("【LLM 请求摘要】model={} apiMessages={} tools={}{}", model, msgCount, toolCount, latestInfo);
        } catch (Exception e) {
            log.warn("【LLM 请求摘要】生成失败", e);
        }
    }

    /**
     * 构建最后一条消息的日志字段，帮助判断进入模型的是文本、工具结果还是多模态内容。
     *
     * @param latestMessage API 请求体中的最后一条 messages[] 元素
     * @return 可直接追加到请求摘要日志的字段串
     */
    @SuppressWarnings("unchecked")
    private String buildLatestMessageLogInfo(Map<String, Object> latestMessage) {
        if (latestMessage == null || latestMessage.isEmpty()) {
            return " latestRole=? hasContentParts=false latestContentChars=0 latestContentFull=\"(empty)\"";
        }

        String role = String.valueOf(latestMessage.getOrDefault("role", "?"));
        Object content = latestMessage.get("content");

        if (content instanceof List<?> contentParts) {
            return buildContentPartsLogInfo(role, contentParts);
        }

        String latestContent;
        if (content instanceof String text && !text.isEmpty()) {
            latestContent = text;
        } else {
            latestContent = summarizeToolCallContent((List<Map<String, Object>>) latestMessage.get("tool_calls"));
        }

        return " latestRole=" + role
                + " hasContentParts=false"
                + " latestContentChars=" + latestContent.length()
                + " latestContentFull=\"" + latestContent + "\"";
    }

    /**
     * 构建多模态 content 数组的安全日志字段，只记录结构信息，不输出图片 base64 或远程 URL 全量。
     *
     * @param role         最新消息角色
     * @param contentParts OpenAI vision 格式的 content 数组
     * @return 多模态结构摘要字段
     */
    private String buildContentPartsLogInfo(String role, List<?> contentParts) {
        List<String> types = new ArrayList<>();
        List<String> imageUrlKinds = new ArrayList<>();
        int imageParts = 0;

        for (Object part : contentParts) {
            if (!(part instanceof Map<?, ?> partMap)) {
                types.add("?");
                continue;
            }

            Object typeValue = partMap.get("type");
            String type = typeValue != null ? String.valueOf(typeValue) : "?";
            types.add(type);

            if ("image_url".equals(type)) {
                imageParts++;
                imageUrlKinds.add(classifyImageUrlKind(partMap.get("image_url")));
            }
        }

        return " latestRole=" + role
                + " hasContentParts=true"
                + " contentParts=" + contentParts.size()
                + " contentPartTypes=" + types
                + " imageParts=" + imageParts
                + " imageUrlKinds=" + imageUrlKinds;
    }

    /**
     * 从 assistant tool_calls 消息中提取工具名，作为没有 content 字段时的可读占位文本。
     *
     * @param toolCalls tool_calls 数组，可为 null
     * @return 工具调用摘要或空消息占位文本
     */
    private String summarizeToolCallContent(List<Map<String, Object>> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "(empty)";
        }

        Map<String, Object> firstToolCall = toolCalls.get(0);
        Object functionValue = firstToolCall.get("function");
        if (!(functionValue instanceof Map<?, ?> function)) {
            return "[tool_call: ?]";
        }

        Object nameValue = function.get("name");
        String toolName = nameValue != null ? String.valueOf(nameValue) : "?";
        return "[tool_call: " + toolName + "]";
    }

    /**
     * 分类图片 URL 来源，避免日志记录图片正文、base64 或完整外部地址。
     *
     * @param imageUrlValue image_url 字段，通常是包含 url 的对象
     * @return 图片 URL 类型
     */
    private String classifyImageUrlKind(Object imageUrlValue) {
        if (!(imageUrlValue instanceof Map<?, ?> imageUrl)) {
            return "unknown";
        }

        Object urlValue = imageUrl.get("url");
        if (!(urlValue instanceof String url) || url.isBlank()) {
            return "empty";
        }
        if (url.startsWith("data:")) {
            return "data-url";
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return "http-url";
        }
        return "other";
    }

    @SuppressWarnings("unchecked")
    private Flux<String> callLlmStream(Map<String, Object> requestBody) {
        customizeRequestBody(requestBody);
        logRequestBody(requestBody);
        AtomicBoolean responseStarted = new AtomicBoolean(false);

        // 流式响应累积器：收集关键信息，完成后统一输出
        StringBuilder accContent = new StringBuilder();
        StringBuilder accReasoning = new StringBuilder();
        String[] accToolName = {null};
        StringBuilder accToolArgs = new StringBuilder();
        int[] accUsage = {0, 0, 0, 0}; // prompt, completion, total, cacheHit
        int[] chunkCount = {0};
        String[] finishReason = {null};

        Flux<String> responseFlux = webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .exchangeToFlux(response -> {
                    int status = response.statusCode().value();
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(String.class)
                                .doOnNext(chunk -> {
                                    responseStarted.set(true);
                                    validateStreamFinishReason(chunk);
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
                                return Flux.error(errorPolicy.httpError(status, responseBody));
                            });
                });

        if (errorPolicy.maxRetries() > 0) {
            responseFlux = responseFlux.retryWhen(buildRetrySpec(() -> !responseStarted.get()));
        }

        return responseFlux.onErrorMap(errorPolicy::normalize);
    }

    /**
     * 校验单个流式响应块中的结束原因。
     *
     * <p>DeepSeek 返回 length、content_filter 或资源不足时立即终止流，
     * 让上层发送明确的 SSE 错误事件。</p>
     *
     * @param chunk 上游 SSE 数据块
     */
    @SuppressWarnings("unchecked")
    private void validateStreamFinishReason(String chunk) {
        String json = normalizeStreamJson(chunk);
        if (json == null) {
            return;
        }

        try {
            Map<String, Object> response = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }

            String finishReason = (String) choices.get(0).get("finish_reason");
            RuntimeException error = errorPolicy.finishReasonError(finishReason);
            if (error != null) {
                throw error;
            }
        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            log.debug("解析流式结束原因失败", e);
        }
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
     *   <li>tool_call — 工具调用（按 tool_calls[].index 累积，流完成时按序统一发出多个）</li>
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

                // 按 OpenAI tool_calls[].index 分开累积（一轮可能并发多个工具调用，参数跨多个 chunk）
                Map<Integer, LlmToolCall.Builder> toolCallBuilders = new java.util.TreeMap<>();
                AtomicReference<LlmUsage> usageRef = new AtomicReference<>();

                callLlmStream(apiFormat)
                    .subscribe(
                        chunk -> {
                            if (emitter.isCancelled()) return;

                            // 只累积 token/reasoning 直接透传，工具调用累积到 map，完成时统一发出
                            List<LlmStreamChunk> chunks = parseStreamChunkWithTools(chunk, toolCallBuilders, usageRef);
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
                                // 按 index 顺序发出所有累积的工具调用（TreeMap 保序）
                                for (LlmToolCall.Builder builder : toolCallBuilders.values()) {
                                    if (builder != null && builder.getName() != null) {
                                        emitter.next(LlmStreamChunk.toolCall(builder.build()));
                                    }
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
     * 解析流式响应（带工具支持）。
     *
     * <p>按 {@code tool_calls[].index} 分开累积多个工具调用（参数跨多个 chunk），
     * <b>不</b>在 finish_reason 处发出——所有累积的工具调用在流完成时（onComplete）
     * 由调用方按 index 顺序统一发出，避免并发下多个工具调用被中途截断或漏发。</p>
     *
     * <p>OpenAI 流式格式：</p>
     * <pre>
     * data: {"choices":[{"delta":{"content":"xxx"}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"web_search","arguments":"..."}}]}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"read_file","arguments":"..."}}]}}]}
     * data: [DONE]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private List<LlmStreamChunk> parseStreamChunkWithTools(String chunk,
                                                            Map<Integer, LlmToolCall.Builder> toolCallBuilders,
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

            // 3. 处理工具调用（按 index 分开累积；并发下一轮多个工具，参数跨多个 chunk）
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (Map<String, Object> tc : toolCalls) {
                    // 以 OpenAI 的 index 作为该工具调用的稳定键；缺失时退化为 0（单工具兼容）
                    int index = tc.get("index") instanceof Number n ? n.intValue() : 0;
                    LlmToolCall.Builder builder = toolCallBuilders.computeIfAbsent(index, k -> LlmToolCall.builder());

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

            // 注：不在 finish_reason 处发出 tool_call。所有累积的工具调用在流完成时（onComplete）
            // 按 index 顺序统一发出，确保并发的多个工具调用不被中途截断或漏发。

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
