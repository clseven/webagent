package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.repository.KnowledgeChunkRepository;
import com.example.sandbox.web.service.EmbeddingService;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.VectorStoreService;
import com.example.sandbox.web.service.enhance.KnowledgeEnhancer;
import com.example.sandbox.web.service.enhance.QueryRewriteService;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RawChunk;
import com.example.sandbox.web.service.enhance.RerankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 知识库检索增强服务实现
 *
 * <p>执行 Step 1-6 完整流程：</p>
 * <ol>
 *   <li>Query Rewrite</li>
 *   <li>向量检索（并行）</li>
 *   <li>融合去重</li>
 *   <li>Rerank</li>
 *   <li>截取 topK</li>
 *   <li>构建上下文文本</li>
 * </ol>
 *
 * @author example
 * @date 2026/06/05
 */
@Service
public class KnowledgeEnhancerImpl implements KnowledgeEnhancer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEnhancerImpl.class);

    @Autowired
    private RagConfigProperties config;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private RerankService rerankService;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Override
    public String enhance(Long userId, List<Long> kbIds, String userMessage, List<ChatMessage> history) {
        RagConfigProperties.Enhancement enhancementConfig = config.getEnhancement();
        if (!enhancementConfig.isEnabled()) {
            return "";
        }

        if (kbIds == null || kbIds.isEmpty()) {
            log.debug("未指定知识库，跳过检索增强");
            return "";
        }

        try {
            long startTime = System.currentTimeMillis();

            // Step 1: Query Rewrite
            List<String> queries = queryRewriteService.rewrite(userMessage, history);
            log.debug("Query Rewrite 结果: {}", queries);

            // Step 2: 向量检索（并行）
            List<RawChunk> candidates = parallelSearch(userId, kbIds, queries, enhancementConfig.getRetrieve().getTopN());
            log.debug("向量检索召回 {} 个候选", candidates.size());

            if (candidates.isEmpty()) {
                return "";
            }

            // Step 3: 融合去重
            List<RawChunk> deduped = deduplicate(candidates, enhancementConfig.getRetrieve().getMinScore());
            log.debug("去重后剩余 {} 个候选", deduped.size());

            if (deduped.isEmpty()) {
                return "";
            }

            // Step 4: Rerank
            List<RankedChunk> ranked = rerankService.rerank(userMessage, deduped);
            log.debug("Rerank 后得到 {} 个结果", ranked.size());

            // Step 5-6: 补充文档名 + 构建上下文
            List<RankedChunk> enriched = enrichWithDocName(ranked);
            String context = buildContext(enriched);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("检索增强完成: 耗时 {}ms, 最终 {} 个片段", elapsed, enriched.size());

            return context;

        } catch (Exception e) {
            log.error("检索增强失败", e);
            return ""; // 失败不影响主流程
        }
    }

    /**
     * 并行向量检索
     */
    private List<RawChunk> parallelSearch(Long userId, List<Long> kbIds, List<String> queries, int topN) {
        // 批量向量化所有 query
        EmbeddingService.EmbeddingResult embeddingResult = embeddingService.embedBatch(queries);
        List<float[]> embeddings = embeddingResult.embeddings();

        if (embeddings.isEmpty()) {
            return List.of();
        }

        // 并行检索
        List<CompletableFuture<List<RawChunk>>> futures = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            final int queryIndex = i;
            final float[] queryEmbedding = embeddings.get(i);

            // 对每个 kbId 检索
            for (Long kbId : kbIds) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Map<String, Object>> results = vectorStoreService.search(userId, kbId, queryEmbedding, topN);
                        return results.stream()
                                .map(r -> new RawChunk(
                                        ((Number) r.get("docId")).longValue(),
                                        ((Number) r.get("chunkIndex")).intValue(),
                                        null, // content 后续填充
                                        ((Number) r.get("score")).floatValue()
                                ))
                                .toList();
                    } catch (Exception e) {
                        log.warn("向量检索失败: kbId={}, queryIndex={}", kbId, queryIndex, e);
                        return List.<RawChunk>of();
                    }
                }));
            }
        }

        // 等待所有检索完成
        List<RawChunk> allCandidates = new ArrayList<>();
        for (CompletableFuture<List<RawChunk>> future : futures) {
            try {
                allCandidates.addAll(future.get());
            } catch (Exception e) {
                log.warn("等待检索结果失败", e);
            }
        }

        return allCandidates;
    }

    /**
     * 融合去重
     */
    private List<RawChunk> deduplicate(List<RawChunk> candidates, float minScore) {
        // 按 (docId, chunkIndex) 分组，保留最高分
        Map<String, RawChunk> dedupMap = new LinkedHashMap<>();
        for (RawChunk c : candidates) {
            String key = c.docId() + ":" + c.chunkIndex();
            RawChunk existing = dedupMap.get(key);
            if (existing == null || c.score() > existing.score()) {
                dedupMap.put(key, c);
            }
        }

        // 过滤低分
        return dedupMap.values().stream()
                .filter(c -> c.score() >= minScore)
                .collect(Collectors.toList());
    }

    /**
     * 补充文档名和内容
     */
    private List<RankedChunk> enrichWithDocName(List<RankedChunk> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        // 批量查询文档名
        Set<Long> docIds = chunks.stream().map(RankedChunk::docId).collect(Collectors.toSet());
        Map<Long, String> docNameMap = new HashMap<>();
        for (Long docId : docIds) {
            try {
                KnowledgeDocumentEntity doc = knowledgeService.getDocument(docId);
                if (doc != null) {
                    docNameMap.put(docId, doc.getFileName());
                }
            } catch (Exception e) {
                log.warn("获取文档名失败: docId={}", docId);
            }
        }

        // 批量查询切片内容
        Map<Long, Map<Integer, String>> contentMap = new HashMap<>();
        List<KnowledgeChunkEntity> chunkEntities = chunkRepository.findByDocumentIdIn(new ArrayList<>(docIds));
        for (KnowledgeChunkEntity entity : chunkEntities) {
            contentMap.computeIfAbsent(entity.getDocumentId(), k -> new HashMap<>())
                    .put(entity.getChunkIndex(), entity.getContent());
        }

        // 构建结果
        List<RankedChunk> result = new ArrayList<>();
        for (RankedChunk c : chunks) {
            String docName = docNameMap.get(c.docId());
            String content = contentMap.getOrDefault(c.docId(), Map.of()).get(c.chunkIndex());
            if (content != null) {
                result.add(new RankedChunk(c.docId(), c.chunkIndex(), content, c.score(), docName));
            }
        }

        return result;
    }

    /**
     * 构建上下文文本
     */
    private String buildContext(List<RankedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库检索结果（来自用户上传的文档）\n\n");
        sb.append("以下内容可能与用户问题相关，已按相关性排序：\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            RankedChunk c = chunks.get(i);
            String docName = c.docName() != null ? c.docName() : "未知文档";
            sb.append(String.format("[%d] 来源：%s（片段 %d，相关度 %.2f）\n",
                    i + 1, docName, c.chunkIndex(), c.score()));
            sb.append(c.content()).append("\n\n");
        }

        return sb.toString();
    }
}
