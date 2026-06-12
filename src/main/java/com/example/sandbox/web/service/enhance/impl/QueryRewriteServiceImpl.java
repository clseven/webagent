package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.service.enhance.QueryRewriteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewrite 服务实现
 *
 * <p>调用本地 Ollama query-rewrite 模型，将用户最新问题改写成适合 RAG 检索的查询语句。</p>
 * <p>system prompt 已固化在模型 Modelfile 中，后端不再额外传递。</p>
 *
 * @author example
 * @date 2026/06/05
 */
@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteServiceImpl.class);

    /** 用于从 LLM 输出中提取 JSON 数组 */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    @Autowired
    private RagConfigProperties config;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient webClient;

    private WebClient getWebClient() {
        if (webClient == null) {
            RagConfigProperties.Enhancement.Rewrite rewriteConfig = config.getEnhancement().getRewrite();
            webClient = WebClient.builder()
                    .baseUrl(rewriteConfig.getApiUrl())
                    .build();
        }
        return webClient;
    }

    @Override
    public List<String> rewrite(String userMessage, List<ChatMessage> history) {
        RagConfigProperties.Enhancement.Rewrite rewriteConfig = config.getEnhancement().getRewrite();
        if (!rewriteConfig.isEnabled()) {
            return List.of(userMessage);
        }

        try {
            String historyText = buildHistoryText(history);

            String userContent = """
                    对话历史：
                    %s

                    当前问题：
                    %s

                    改写结果（JSON 数组）：
                    """.formatted(historyText, userMessage);

            // 构建 Ollama 请求体
            Map<String, Object> request = buildOllamaRequest(rewriteConfig, userContent);

            String response = getWebClient().post()
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // 从 Ollama 响应中提取 message.content
            String content = extractContent(response);

            List<String> queries = parseQueries(content, rewriteConfig.getMaxQueries());

            if (queries.isEmpty()) {
                log.warn("Query Rewrite 返回空，降级为原始 query");
                return List.of(userMessage);
            }

            log.info("Query Rewrite: {} -> {}", userMessage, queries);
            return queries;

        } catch (Exception e) {
            log.warn("Query Rewrite 失败，降级为原始 query: {}", e.getMessage());
            return List.of(userMessage);
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是 RAG 检索中的 Query Rewrite 模型。

            你的任务：
            把用户最新问题改写成独立、清晰、适合检索的 query。

            只允许做以下改写：
            1. 指代消解：把"它、这个、那个、上面、刚才"等替换为对话历史中的具体对象。
            2. 省略补全：补全被省略的主语、对象、上下文。
            3. 口语规范化：把"咋整、啥意思、跑不起来、连不上、报错了"等口语表达改成更适合检索的表达。
            4. 技术名词规范化：保留或规范技术名词大小写，例如 springboot 可返回 springboot 或 Spring Boot。

            禁止：
            1. 不要猜测用户没有表达的具体主题。
            2. 不要把宽泛关键词改成具体子问题。
            3. 不要新增用户没有提到的信息。
            4. 不要回答问题。
            5. 不要输出对象数组。

            输出要求：
            1. 只输出 JSON 字符串数组。
            2. 数组元素必须是字符串。
            3. 返回 1 到 3 条 query。
            4. 不要解释。
            5. 不要 Markdown。

            示例：
            用户问题：springboot
            输出：["springboot", "Spring Boot"]

            用户问题：springboot咋启动
            输出：["Spring Boot 如何启动"]

            用户问题：这玩意儿连不上咋整
            对话历史：用户正在讨论 WebSocket
            输出：["WebSocket 连接失败怎么处理"]

            用户问题：这个报错啥意思
            对话历史：用户正在讨论 NullPointerException
            输出：["NullPointerException 报错的含义和原因"]
            """;

    /**
     * 构建 Ollama /api/chat 请求体
     */
    private Map<String, Object> buildOllamaRequest(
            RagConfigProperties.Enhancement.Rewrite rewriteConfig, String userContent) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", rewriteConfig.getModel());
        request.put("stream", false);
        request.put("think", false);
        request.put("format", "json");
        request.put("keep_alive", rewriteConfig.getKeepAlive());
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userContent)
        ));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", rewriteConfig.getTemperature());
        options.put("top_p", rewriteConfig.getTopP());
        options.put("num_predict", rewriteConfig.getNumPredict());
        options.put("num_ctx", rewriteConfig.getNumCtx());
        request.put("options", options);

        return request;
    }

    /**
     * 从 Ollama 响应 JSON 中提取 message.content
     */
    private String extractContent(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("message") && root.get("message").has("content")) {
                return root.get("message").get("content").asText();
            }
        } catch (Exception e) {
            log.debug("解析 Ollama 响应失败: {}", e.getMessage());
        }
        return response;
    }

    /**
     * 构建历史对话文本（取最近 N 条）
     */
    private String buildHistoryText(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 6); // 最近 6 条
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 数组
     */
    private List<String> parseQueries(String response, int maxQueries) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        // 尝试匹配 JSON 数组
        Matcher m = JSON_ARRAY_PATTERN.matcher(response);
        if (m.find()) {
            String json = m.group();
            try {
                List<String> queries = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                // 过滤空字符串，限制数量
                return queries.stream()
                        .filter(q -> q != null && !q.isBlank())
                        .limit(maxQueries)
                        .toList();
            } catch (Exception e) {
                log.debug("解析 JSON 数组失败: {}", e.getMessage());
            }
        }

        // 降级：尝试按行/逗号分割
        String[] parts = response.split("[\n,]");
        List<String> queries = new ArrayList<>();
        for (String part : parts) {
            String q = part.trim().replaceAll("^[\"'\\[\\]]+|[\"'\\[\\]]+$", "");
            if (!q.isEmpty() && queries.size() < maxQueries) {
                queries.add(q);
            }
        }

        return queries;
    }
}
