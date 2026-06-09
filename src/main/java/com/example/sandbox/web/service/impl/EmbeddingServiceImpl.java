package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.service.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智谱 Embedding API 服务实现
 *
 * @author example
 * @date 2026/05/31
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);

    @Autowired
    private RagConfigProperties config;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public EmbeddingResult embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResult(new ArrayList<>(), 0);
        }

        List<float[]> allEmbeddings = new ArrayList<>();
        int totalTokens = 0;
        int batchSize = config.getEmbedding().getBatchSize();

        // 分批处理
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            EmbeddingResult batchResult = callEmbeddingApi(batch);
            allEmbeddings.addAll(batchResult.embeddings());
            totalTokens += batchResult.promptTokens();
        }

        log.info("Embedding 完成: 文本数={}, 总 tokens={}", texts.size(), totalTokens);
        return new EmbeddingResult(allEmbeddings, totalTokens);
    }

    private EmbeddingResult callEmbeddingApi(List<String> texts) {
        try {
            // 配置更大的缓冲区（10MB）
            WebClient client = WebClient.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();

            Map<String, Object> requestBody = Map.of(
                    "model", config.getEmbedding().getModel(),
                    "input", texts
            );

            log.info("调用 Embedding API: url={}, model={}, texts={}", config.getEmbedding().getApiUrl(), config.getEmbedding().getModel(), texts.size());

            String response = client.post()
                    .uri(config.getEmbedding().getApiUrl())
                    .header("Authorization", "Bearer " + config.getEmbedding().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("Embedding API 响应成功");
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Embedding API 调用失败", e);
            throw new RuntimeException("Embedding API 调用失败: " + e.getMessage(), e);
        }
    }

    private EmbeddingResult parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);

            // 解析向量
            JsonNode data = root.get("data");
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embeddingNode = item.get("embedding");
                float[] vector = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    vector[i] = (float) embeddingNode.get(i).asDouble();
                }
                embeddings.add(vector);
            }

            // 解析 token 用量
            int promptTokens = 0;
            JsonNode usage = root.get("usage");
            if (usage != null && usage.has("prompt_tokens")) {
                promptTokens = usage.get("prompt_tokens").asInt();
            }

            return new EmbeddingResult(embeddings, promptTokens);
        } catch (Exception e) {
            log.error("解析 Embedding 响应失败: {}", response, e);
            throw new RuntimeException("解析 Embedding 响应失败", e);
        }
    }
}
