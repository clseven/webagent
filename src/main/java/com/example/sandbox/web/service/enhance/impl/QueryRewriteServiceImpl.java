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
 * <p>调用 OpenAI 兼容的对话模型（默认 DeepSeek），将用户最新问题改写成适合 RAG 检索的查询语句。</p>
 * <p>为提升召回完整性，会围绕用户问题生成多角度 query（忠实版 / 同义版 / 子问题拆解），
 * 并通过 JSON mode 强制模型输出结构化结果。</p>
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
            WebClient.Builder builder = WebClient.builder()
                    .baseUrl(rewriteConfig.getApiUrl())
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024));
            if (rewriteConfig.getApiKey() != null && !rewriteConfig.getApiKey().isBlank()) {
                builder.defaultHeader("Authorization", "Bearer " + rewriteConfig.getApiKey());
            }
            webClient = builder.build();
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

            // 构建 OpenAI 兼容的 /chat/completions 请求体
            Map<String, Object> request = buildRequest(rewriteConfig, userContent);

            String response = getWebClient().post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // 从 OpenAI 兼容响应中提取 choices[0].message.content
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
            你是 RAG 检索的 Query Rewrite 模型。目标是「尽可能召回完整」：
            围绕用户问题生成多个角度的检索 query，覆盖问题可能涉及的所有内容。

            输入包含：
            - 对话历史（可能为空）
            - 用户的最新问题

            生成 query 的策略（按需组合，不是每条都必须用）：
            1. 忠实版（必须有，放在第一条）：
               对最新问题做指代消解和省略补全，得到一条最贴近用户原意、可独立检索的 query。
               - 指代消解："它、这个、那个、上面、刚才"→ 用对话历史里的具体对象替换。
               - 省略补全：补上被省略的主语或对象。
               - 口语规范化："咋整、啥意思、连不上、报错了"→ 书面技术表达。
               - 技术名词规范化：如 springboot → Spring Boot。
            2. 同义版（推荐）：
               用不同的技术术语或表述，把忠实版换 1~2 种说法，覆盖文档里可能的不同措辞。
               例："启动" 与 "运行 / 部署"。
            3. 子问题版（仅当问题是复合问题时）：
               若用户问题包含多个方面（对比、多个步骤、原因+解决），
               拆成 2~4 条各自独立、可分别检索的子问题。

            硬性要求：
            1. 第一条永远是忠实版，不得偏离用户原意。
            2. 不臆造用户没提到的主题、技术或细节；所有 query 必须能从原问题或历史推出。
            3. 不回答问题、不加任何解释。
            4. 宁可精准，不要为凑数量而生成不相关的 query。

            输出要求：
            1. 只输出一个 JSON 对象，形如 {"queries": ["...", "..."]}，不要 Markdown、不要多余文字。
            2. queries 是字符串数组，每个元素是一条检索 query。
            3. 简单问题返回 1~2 条；复合/复杂问题可返回 3~5 条。

            示例：
            问题：springboot咋启动
            输出：{"queries": ["Spring Boot 如何启动", "Spring Boot 项目运行方式"]}

            问题：这玩意儿连不上咋整
            历史：正在讨论 WebSocket
            输出：{"queries": ["WebSocket 连接失败怎么处理", "WebSocket 无法建立连接的原因", "WebSocket 连接报错排查"]}

            问题：Redis 和 Memcached 高并发下怎么选
            输出：{"queries": ["Redis 和 Memcached 的区别", "Redis 高并发性能表现", "Memcached 高并发性能表现", "高并发缓存选型 Redis vs Memcached"]}

            问题：你好
            输出：{"queries": ["你好"]}
            """;

    /**
     * 构建 OpenAI 兼容的 /chat/completions 请求体
     *
     * <p>启用 JSON mode（response_format=json_object）强制模型输出合法 JSON，提升稳定性。
     * JSON mode 要求 prompt 中出现 "json" 字样，已在 system prompt 中满足。</p>
     */
    private Map<String, Object> buildRequest(
            RagConfigProperties.Enhancement.Rewrite rewriteConfig, String userContent) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", rewriteConfig.getModel());
        request.put("stream", false);
        request.put("temperature", rewriteConfig.getTemperature());
        request.put("top_p", rewriteConfig.getTopP());
        request.put("max_tokens", rewriteConfig.getMaxTokens());
        request.put("response_format", Map.of("type", "json_object"));
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userContent)
        ));

        return request;
    }

    /**
     * 从 OpenAI 兼容响应 JSON 中提取 choices[0].message.content
     */
    private String extractContent(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.debug("解析 LLM 响应失败: {}", e.getMessage());
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
     * 解析 LLM 返回内容，提取 query 列表
     *
     * <p>优先解析 JSON mode 约定的对象格式 {"queries": [...]}；
     * 若失败则依次降级为「裸 JSON 数组」和「按行/逗号分割」，保证鲁棒。</p>
     */
    private List<String> parseQueries(String response, int maxQueries) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        // 优先：解析 {"queries": ["...", "..."]} 对象格式
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode arr = root.get("queries");
            if (arr != null && arr.isArray()) {
                return collectQueries(arr, maxQueries);
            }
        } catch (Exception e) {
            log.debug("解析 queries 对象失败，尝试降级: {}", e.getMessage());
        }

        // 降级 1：匹配裸 JSON 数组
        Matcher m = JSON_ARRAY_PATTERN.matcher(response);
        if (m.find()) {
            try {
                JsonNode arr = objectMapper.readTree(m.group());
                if (arr.isArray()) {
                    return collectQueries(arr, maxQueries);
                }
            } catch (Exception e) {
                log.debug("解析 JSON 数组失败: {}", e.getMessage());
            }
        }

        // 降级 2：按行/逗号分割
        String[] parts = response.split("[\n,]");
        List<String> queries = new ArrayList<>();
        for (String part : parts) {
            String q = part.trim().replaceAll("^[\"'\\[\\]{}]+|[\"'\\[\\]{}]+$", "");
            if (!q.isEmpty() && queries.size() < maxQueries) {
                queries.add(q);
            }
        }

        return queries;
    }

    /**
     * 从 JSON 数组节点收集非空字符串 query，并限制数量
     */
    private List<String> collectQueries(JsonNode arr, int maxQueries) {
        List<String> queries = new ArrayList<>();
        for (JsonNode node : arr) {
            String q = node.asText();
            if (q != null && !q.isBlank()) {
                queries.add(q.trim());
                if (queries.size() >= maxQueries) {
                    break;
                }
            }
        }
        return queries;
    }
}
