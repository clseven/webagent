package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RawChunk;
import com.example.sandbox.web.service.enhance.RerankService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ollama Rerank 服务实现
 *
 * <p>通过 Ollama API 调用本地部署的 bge-reranker 模型。</p>
 *
 * <h3>API 格式</h3>
 * <pre>
 * POST /api/chat
 * {
 *   "model": "bge-reranker",
 *   "messages": [{"role": "user", "content": "..."}]
 * }
 * </pre>
 *
 * @author example
 * @date 2026/06/05
 */
@Service
public class OllamaRerankServiceImpl implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(OllamaRerankServiceImpl.class);

    /** 用于从模型输出中提取分数的正则 */
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\b");

    @Autowired
    private RagConfigProperties config;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient webClient;

    private WebClient getWebClient() {
        if (webClient == null) {
            RagConfigProperties.Enhancement.Rerank rerankConfig = config.getEnhancement().getRerank();
            webClient = WebClient.builder()
                    .baseUrl(rerankConfig.getApiUrl())
                    .build();
        }
        return webClient;
    }

    @Override
    public List<RankedChunk> rerank(String query, List<RawChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        RagConfigProperties.Enhancement.Rerank rerankConfig = config.getEnhancement().getRerank();
        if (!rerankConfig.isEnabled()) {
            // Rerank 未启用，直接按原始分数排序
            return candidates.stream()
                    .sorted((a, b) -> Float.compare(b.score(), a.score()))
                    .limit(rerankConfig.getTopK())
                    .map(c -> new RankedChunk(c.docId(), c.chunkIndex(), c.content(), c.score(), null))
                    .toList();
        }

        try {
            // 构建 prompt：让模型对每个候选打分
            String prompt = buildRerankPrompt(query, candidates);

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", rerankConfig.getModel());
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("stream", false);

            String response = getWebClient().post()
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<Float> scores = parseScores(response, candidates.size());

            // 合并分数并排序
            List<RankedChunk> ranked = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                RawChunk c = candidates.get(i);
                float score = i < scores.size() ? scores.get(i) : 0f;
                ranked.add(new RankedChunk(c.docId(), c.chunkIndex(), c.content(), score, null));
            }

            ranked.sort((a, b) -> Float.compare(b.score(), a.score()));

            // 截取 topK
            int topK = rerankConfig.getTopK();
            return ranked.size() <= topK ? ranked : ranked.subList(0, topK);

        } catch (Exception e) {
            log.warn("Rerank 调用失败，降级为向量分数排序: {}", e.getMessage());
            // 降级：用原始向量分数
            return candidates.stream()
                    .sorted((a, b) -> Float.compare(b.score(), a.score()))
                    .limit(rerankConfig.getTopK())
                    .map(c -> new RankedChunk(c.docId(), c.chunkIndex(), c.content(), c.score(), null))
                    .toList();
        }
    }

    /**
     * 构建 Rerank prompt
     *
     * <p>让模型对每个候选片段打分（0-10）</p>
     */
    private String buildRerankPrompt(String query, List<RawChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对以下候选文档片段与查询的相关性进行打分（0-10分，10分最相关）。\n\n");
        sb.append("查询：").append(query).append("\n\n");
        sb.append("候选片段：\n");

        for (int i = 0; i < candidates.size(); i++) {
            RawChunk c = candidates.get(i);
            sb.append(String.format("[%d] %s\n\n", i + 1, truncate(c.content(), 300)));
        }

        sb.append("请按顺序输出每个片段的分数，用逗号分隔，例如：8,5,3,9,2\n");
        sb.append("只输出分数，不要输出其他内容。");

        return sb.toString();
    }

    /**
     * 从模型输出解析分数
     */
    private List<Float> parseScores(String response, int expectedCount) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        try {
            // 尝试解析 JSON 格式响应（Ollama 返回格式）
            JsonNode root = objectMapper.readTree(response);
            if (root.has("message") && root.get("message").has("content")) {
                String content = root.get("message").get("content").asText();
                return extractScores(content, expectedCount);
            }
        } catch (Exception e) {
            log.debug("非 JSON 响应，尝试直接解析: {}", response);
        }

        // 直接解析（可能是纯文本）
        return extractScores(response, expectedCount);
    }

    private List<Float> parseScoresFromJson(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("message") && root.get("message").has("content")) {
                String content = root.get("message").get("content").asText();
                return extractScores(content, 10);
            }
        } catch (Exception e) {
            log.debug("解析 JSON 失败: {}", e.getMessage());
        }
        return List.of();
    }

    private List<Float> extractScores(String content, int expectedCount) {
        List<Float> scores = new ArrayList<>();

        // 尝试按逗号分割
        String[] parts = content.split("[,，\\s]+");
        for (String part : parts) {
            try {
                float score = Float.parseFloat(part.trim());
                // 归一化到 0-1（假设模型输出 0-10）
                scores.add(Math.min(score / 10f, 1f));
                if (scores.size() >= expectedCount) break;
            } catch (NumberFormatException e) {
                // 尝试用正则提取数字
                Matcher m = SCORE_PATTERN.matcher(part);
                if (m.find()) {
                    float score = Float.parseFloat(m.group(1));
                    scores.add(Math.min(score / 10f, 1f));
                    if (scores.size() >= expectedCount) break;
                }
            }
        }

        return scores;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
