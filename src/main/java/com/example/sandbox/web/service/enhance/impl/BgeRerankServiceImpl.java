package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RawChunk;
import com.example.sandbox.web.service.enhance.RerankService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * BGE Reranker 服务实现
 *
 * <p>调用本地部署的 BAAI/bge-reranker-v2-m3 标准 rerank API。</p>
 *
 * @author example
 * @date 2026/06/05
 */
@Service
public class BgeRerankServiceImpl implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankServiceImpl.class);

    @Autowired
    private RagConfigProperties config;

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
            return candidates.stream()
                    .sorted((a, b) -> Float.compare(b.score(), a.score()))
                    .limit(rerankConfig.getTopK())
                    .map(c -> new RankedChunk(c.docId(), c.chunkIndex(), c.content(), c.score(), null))
                    .toList();
        }

        try {
            // 提取 passages
            List<String> passages = candidates.stream()
                    .map(RawChunk::content)
                    .toList();

            // 构建请求
            RerankRequest request = new RerankRequest(query, passages, rerankConfig.getTopK(), true);

            // 调用 /rerank API
            RerankResponse response = getWebClient().post()
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .block();

            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                log.warn("Rerank 返回空结果，降级为向量分数排序");
                return fallbackSort(candidates, rerankConfig.getTopK());
            }

            // 映射结果
            List<RankedChunk> ranked = new ArrayList<>();
            for (RerankResponse.RerankItem item : response.getResults()) {
                int idx = item.getIndex();
                if (idx >= 0 && idx < candidates.size()) {
                    RawChunk c = candidates.get(idx);
                    ranked.add(new RankedChunk(c.docId(), c.chunkIndex(), c.content(), item.getScore().floatValue(), null));
                }
            }

            return ranked;

        } catch (Exception e) {
            log.warn("Rerank 调用失败，降级为向量分数排序: {}", e.getMessage());
            return fallbackSort(candidates, rerankConfig.getTopK());
        }
    }

    private List<RankedChunk> fallbackSort(List<RawChunk> candidates, int topK) {
        return candidates.stream()
                .sorted((a, b) -> Float.compare(b.score(), a.score()))
                .limit(topK)
                .map(c -> new RankedChunk(c.docId(), c.chunkIndex(), c.content(), c.score(), null))
                .toList();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankRequest {
        private String query;
        private List<String> passages;
        private Integer topK;
        private Boolean normalize;
    }

    @Data
    @NoArgsConstructor
    public static class RerankResponse {
        private String query;
        private Integer topK;
        private Integer total;
        private List<RerankItem> results;

        @Data
        @NoArgsConstructor
        public static class RerankItem {
            private Integer index;
            private Integer rank;
            private Double score;
            private String text;
        }
    }
}
