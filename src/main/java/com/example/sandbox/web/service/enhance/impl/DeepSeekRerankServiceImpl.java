package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RawChunk;
import com.example.sandbox.web.service.enhance.RerankResult;
import com.example.sandbox.web.service.enhance.RerankService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek 知识库重排服务。
 *
 * <h3>用途</h3>
 * <p>将有限数量的向量召回候选一次性发送给独立的 DeepSeek Flash 模型，
 * 解析模型返回的候选索引并映射为领域层 {@link RankedChunk}。</p>
 *
 * <h3>故障策略</h3>
 * <p>HTTP 失败、超时、空响应和协议错误均不重试，立即按原始向量分数降级，
 * 避免外部模型阻断用户检索请求。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "rag.enhancement.rerank",
        name = "provider",
        havingValue = "deepseek",
        matchIfMissing = true)
public class DeepSeekRerankServiceImpl implements RerankService {

    /** 结构化运行日志。 */
    private static final Logger log = LoggerFactory.getLogger(DeepSeekRerankServiceImpl.class);
    /** 限制模型只返回候选排序 JSON 的系统提示词。 */
    private static final String SYSTEM_PROMPT = """
            你是知识库检索重排器。请根据用户查询判断候选片段的语义相关性。
            只允许返回这个结构：{"results":[{"index":候选索引,"score":0到1的小数}]}。
            results 只返回 topK 条并按相关性从高到低排列，每个候选索引最多出现一次。
            禁止返回 text、content、explanation 等字段，禁止复制候选正文，禁止解释和 Markdown。
            """;

    /** RAG 配置，模型名与主流程模型配置相互独立。 */
    private final RagConfigProperties config;
    /** JSON 序列化和响应解析工具。 */
    private final ObjectMapper objectMapper;
    /** 只用于重排请求的 HTTP 客户端。 */
    private final WebClient webClient;

    /**
     * 使用独立 RAG 配置创建 DeepSeek 重排客户端。
     *
     * @param config RAG 配置
     * @param objectMapper JSON 序列化工具
     */
    public DeepSeekRerankServiceImpl(RagConfigProperties config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        RagConfigProperties.Enhancement.Rerank rerank = config.getEnhancement().getRerank();
        WebClient.Builder builder = WebClient.builder().baseUrl(rerank.getApiUrl());
        if (rerank.getApiKey() != null && !rerank.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rerank.getApiKey());
        }
        this.webClient = builder.build();
    }

    /**
     * 对候选片段执行一次 DeepSeek 批量重排。
     *
     * @param query 原始用户查询
     * @param candidates 向量召回候选
     * @return 结构化重排结果；外部调用失败时返回带降级标记的向量排序结果
     */
    @Override
    public RerankResult rerank(String query, List<RawChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return RerankResult.vectorFallback(List.of());
        }

        RagConfigProperties.Enhancement.Rerank rerank = config.getEnhancement().getRerank();
        int topK = Math.max(0, Math.min(rerank.getTopK(), candidates.size()));
        if (topK == 0) {
            return RerankResult.vectorFallback(List.of());
        }
        if (!rerank.isEnabled() || query == null || query.isBlank()) {
            return RerankResult.vectorFallback(fallbackSort(candidates, topK));
        }

        List<RawChunk> selected = candidates.stream()
                .sorted(Comparator.comparingDouble(RawChunk::score).reversed())
                .limit(Math.max(1, rerank.getMaxCandidates()))
                .toList();
        long started = System.nanoTime();
        try {
            JsonNode response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(buildRequest(query, selected, topK, rerank))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(Math.max(1, rerank.getTimeoutSeconds())))
                    .block();
            List<RankedChunk> ranked = parseResponse(response, selected, topK);
            log.info("重排完成: provider=deepseek, model={}, candidates={}, topK={}, elapsedMs={}, fallback=false",
                    rerank.getModel(), selected.size(), topK, elapsedMillis(started));
            return RerankResult.reranked(ranked);
        } catch (Exception e) {
            log.warn("重排降级: provider=deepseek, model={}, candidates={}, topK={}, elapsedMs={}, reason={}",
                    rerank.getModel(), selected.size(), topK, elapsedMillis(started),
                    safeFailureReason(e));
            return RerankResult.vectorFallback(fallbackSort(candidates, topK));
        }
    }

    /**
     * 构造不含工具定义、显式关闭思考模式的重排请求。
     *
     * @param query 原始用户查询
     * @param selected 经过向量分数预筛的候选
     * @param topK 期望返回的最大条数
     * @param rerank 重排配置
     * @return 可直接发送给 DeepSeek 的请求体
     * @throws JsonProcessingException 候选请求无法序列化时抛出
     */
    private Map<String, Object> buildRequest(
            String query,
            List<RawChunk> selected,
            int topK,
            RagConfigProperties.Enhancement.Rerank rerank) throws JsonProcessingException {
        List<Map<String, Object>> candidatePayload = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            candidatePayload.add(Map.of(
                    "index", i,
                    "text", truncate(selected.get(i).content(), rerank.getMaxContentChars())));
        }
        String userPayload = objectMapper.writeValueAsString(Map.of(
                "query", query,
                "topK", topK,
                "candidates", candidatePayload));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", rerank.getModel());
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPayload)));
        request.put("temperature", 0);
        request.put("max_tokens", rerank.getMaxTokens());
        request.put("thinking", Map.of("type", "disabled"));
        return request;
    }

    /**
     * 解析模型返回的索引和分数。
     *
     * <p>成功响应只保留模型明确给出分数的候选，不再使用向量分数补足，
     * 从而保证一次成功结果中的每个分数都具有相同来源。</p>
     *
     * @param response DeepSeek 原始 JSON 响应
     * @param selected 发送给模型的候选列表
     * @param topK 期望返回的最大条数
     * @return 已校验并去重的模型重排结果
     * @throws JsonProcessingException 模型正文不是合法 JSON 时抛出
     */
    private List<RankedChunk> parseResponse(JsonNode response, List<RawChunk> selected, int topK)
            throws JsonProcessingException {
        if (response == null) {
            throw new IllegalArgumentException("DeepSeek 重排响应为空");
        }
        String content = response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("DeepSeek 重排结果为空");
        }
        JsonNode parsed = objectMapper.readTree(extractJsonValue(content));
        JsonNode items = parsed.isArray() ? parsed : parsed.path("results");
        if (!items.isArray()) {
            throw new IllegalArgumentException("DeepSeek 重排结果缺少 results 数组");
        }

        List<RankedChunk> ranked = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (JsonNode item : items) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= selected.size()
                    || !item.path("score").isNumber() || !used.add(index)) {
                continue;
            }
            RawChunk candidate = selected.get(index);
            float score = (float) Math.max(0.0, Math.min(1.0, item.path("score").asDouble()));
            ranked.add(toRankedChunk(candidate, score));
            if (ranked.size() >= topK) {
                return ranked;
            }
        }
        if (ranked.isEmpty()) {
            throw new IllegalArgumentException("DeepSeek 重排没有可用索引");
        }
        return ranked;
    }

    /**
     * 按原始向量分数降序生成确定性降级结果。
     *
     * @param candidates 原始向量候选
     * @param topK 最大返回条数
     * @return 向量分数降序结果
     */
    private List<RankedChunk> fallbackSort(List<RawChunk> candidates, int topK) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RawChunk::score).reversed())
                .limit(topK)
                .map(candidate -> toRankedChunk(candidate, candidate.score()))
                .toList();
    }

    /**
     * 将候选转换为重排结果，不在协议层补充文档名。
     *
     * @param candidate 原始候选
     * @param score 最终相关性分数
     * @return 重排领域对象
     */
    private RankedChunk toRankedChunk(RawChunk candidate, float score) {
        return new RankedChunk(
                candidate.docId(), candidate.chunkIndex(), candidate.content(), score, null);
    }

    /**
     * 截断发送给外部模型的正文，数据库原文保持不变。
     *
     * @param content 候选正文
     * @param maxChars 最大字符数
     * @return 安全截断后的正文
     */
    private String truncate(String content, int maxChars) {
        String safeContent = content == null ? "" : content;
        int limit = Math.max(0, maxChars);
        return safeContent.length() <= limit ? safeContent : safeContent.substring(0, limit);
    }

    /**
     * 提取首个完整 JSON 对象或数组，兼容模型误加 Markdown 代码围栏。
     *
     * @param content 模型消息正文
     * @return 可交给 Jackson 解析的 JSON 对象或数组文本
     * @throws IllegalArgumentException 正文不包含完整 JSON 对象或数组时抛出
     */
    private String extractJsonValue(String content) {
        int objectStart = content.indexOf('{');
        int arrayStart = content.indexOf('[');
        boolean useArray = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart);
        int start = useArray ? arrayStart : objectStart;
        int end = useArray ? content.lastIndexOf(']') : content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("DeepSeek 重排结果不是完整 JSON");
        }
        return content.substring(start, end + 1);
    }

    /**
     * 计算单次重排已经消耗的毫秒数。
     *
     * @param startedNanos 开始时的单调时钟纳秒值
     * @return 已耗费毫秒数
     */
    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * 只暴露本类生成的安全协议错误，不把外部响应正文写入日志。
     *
     * @param error 重排调用异常
     * @return 可安全记录的失败原因
     */
    private String safeFailureReason(Exception error) {
        String message = error.getMessage();
        if (message != null && message.startsWith("DeepSeek 重排")) {
            return message;
        }
        return error.getClass().getSimpleName();
    }
}
