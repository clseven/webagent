package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.repository.KnowledgeChunkRepository;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import com.example.sandbox.web.service.EmbeddingService;
import com.example.sandbox.web.service.VectorStoreService;
import com.example.sandbox.web.service.enhance.QueryRewriteService;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RerankResult;
import com.example.sandbox.web.service.enhance.RerankService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Knowledge 页面搜索的最终条数和文档元数据。
 */
class KnowledgeServiceSearchTest {

    /**
     * 验证请求 topK=5 时只返回 5 条，并通过一次批量查询补充真实文件名。
     */
    @Test
    void limitsResultsAndLoadsDocumentNamesInBatch() {
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        RerankService rerank = mock(RerankService.class);
        KnowledgeServiceImpl service = new KnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "queryRewriteService", rewrite);
        ReflectionTestUtils.setField(service, "embeddingService", embedding);
        ReflectionTestUtils.setField(service, "vectorStoreService", vectorStore);
        ReflectionTestUtils.setField(service, "chunkRepository", chunks);
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "rerankService", rerank);
        ReflectionTestUtils.setField(service, "ragConfigProperties", new RagConfigProperties());

        when(rewrite.rewrite("query", List.of())).thenReturn(List.of("query"));
        when(embedding.embedBatch(List.of("query")))
                .thenReturn(new EmbeddingService.EmbeddingResult(List.of(new float[]{0.1f}), 1));

        List<Map<String, Object>> vectorRows = new ArrayList<>();
        List<KnowledgeChunkEntity> chunkRows = new ArrayList<>();
        List<RankedChunk> rankedRows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            float score = 0.9f - i * 0.01f;
            Map<String, Object> vectorRow = new HashMap<>();
            vectorRow.put("docId", 1L);
            vectorRow.put("chunkIndex", i);
            vectorRow.put("score", score);
            vectorRows.add(vectorRow);
            KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
            chunk.setDocumentId(1L);
            chunk.setChunkIndex(i);
            chunk.setContent("chunk-" + i);
            chunkRows.add(chunk);
            rankedRows.add(new RankedChunk(1L, i, "chunk-" + i, score, null));
        }
        when(vectorStore.search(eq(2L), eq(3L), any(float[].class), eq(20)))
                .thenReturn(vectorRows);
        when(chunks.findByDocumentIdIn(List.of(1L))).thenReturn(chunkRows);
        when(rerank.rerank(eq("query"), anyList())).thenReturn(RerankResult.reranked(rankedRows));

        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        ReflectionTestUtils.setField(document, "id", 1L);
        document.setFileName("resume.pdf");
        when(documents.findAllById(List.of(1L))).thenReturn(List.of(document));

        List<Map<String, Object>> results = service.search(2L, 3L, "query", 5);

        assertThat(results).hasSize(5);
        assertThat(results).allSatisfy(
                row -> assertThat(row.get("docName")).isEqualTo("resume.pdf"));
    }

    /**
     * 验证页面传入的最低相关度在重排后、截取 topK 前生效。
     */
    @Test
    void filtersRerankedResultsByRequestedMinimumScore() {
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        RerankService rerank = mock(RerankService.class);
        KnowledgeServiceImpl service = new KnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "queryRewriteService", rewrite);
        ReflectionTestUtils.setField(service, "embeddingService", embedding);
        ReflectionTestUtils.setField(service, "vectorStoreService", vectorStore);
        ReflectionTestUtils.setField(service, "chunkRepository", chunks);
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "rerankService", rerank);
        ReflectionTestUtils.setField(service, "ragConfigProperties", new RagConfigProperties());

        when(rewrite.rewrite("query", List.of())).thenReturn(List.of("query"));
        when(embedding.embedBatch(List.of("query")))
                .thenReturn(new EmbeddingService.EmbeddingResult(List.of(new float[]{0.1f}), 1));
        Map<String, Object> vectorRow = new HashMap<>();
        vectorRow.put("docId", 1L);
        vectorRow.put("chunkIndex", 0);
        vectorRow.put("score", 0.9f);
        when(vectorStore.search(eq(2L), eq(3L), any(float[].class), eq(20)))
                .thenReturn(List.of(vectorRow));

        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setDocumentId(1L);
        chunk.setChunkIndex(0);
        chunk.setContent("chunk");
        when(chunks.findByDocumentIdIn(List.of(1L))).thenReturn(List.of(chunk));
        when(rerank.rerank(eq("query"), anyList())).thenReturn(RerankResult.reranked(List.of(
                new RankedChunk(1L, 0, "high", 1.0f, null),
                new RankedChunk(1L, 1, "low", 0.3f, null))));

        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        ReflectionTestUtils.setField(document, "id", 1L);
        document.setFileName("resume.pdf");
        when(documents.findAllById(List.of(1L))).thenReturn(List.of(document));

        List<Map<String, Object>> results = service.search(2L, 3L, "query", 5, 0.8f);

        assertThat(results).extracting(row -> row.get("score")).containsExactly(1.0f);
    }

    /**
     * 验证多个知识库共享一次查询改写、一次批量向量化和一次全局重排。
     */
    @Test
    void searchesAllScopedKnowledgeBasesWithOneGlobalRerank() {
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        RerankService rerank = mock(RerankService.class);
        KnowledgeServiceImpl service = new KnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "queryRewriteService", rewrite);
        ReflectionTestUtils.setField(service, "embeddingService", embedding);
        ReflectionTestUtils.setField(service, "vectorStoreService", vectorStore);
        ReflectionTestUtils.setField(service, "chunkRepository", chunks);
        ReflectionTestUtils.setField(service, "documentRepository", documents);
        ReflectionTestUtils.setField(service, "rerankService", rerank);
        ReflectionTestUtils.setField(service, "ragConfigProperties", new RagConfigProperties());

        when(rewrite.rewrite("query", List.of())).thenReturn(List.of("query-a", "query-b"));
        when(embedding.embedBatch(List.of("query-a", "query-b"))).thenReturn(
                new EmbeddingService.EmbeddingResult(
                        List.of(new float[]{0.1f}, new float[]{0.2f}), 2));

        Map<String, Object> firstVectorRow = new HashMap<>();
        firstVectorRow.put("docId", 1L);
        firstVectorRow.put("chunkIndex", 0);
        firstVectorRow.put("score", 0.4f);
        Map<String, Object> secondVectorRow = new HashMap<>();
        secondVectorRow.put("docId", 2L);
        secondVectorRow.put("chunkIndex", 0);
        secondVectorRow.put("score", 0.6f);
        when(vectorStore.search(eq(2L), eq(11L), any(float[].class), eq(20)))
                .thenReturn(List.of(firstVectorRow));
        when(vectorStore.search(eq(2L), eq(12L), any(float[].class), eq(20)))
                .thenReturn(List.of(secondVectorRow));

        KnowledgeChunkEntity firstChunk = new KnowledgeChunkEntity();
        firstChunk.setDocumentId(1L);
        firstChunk.setChunkIndex(0);
        firstChunk.setContent("first");
        KnowledgeChunkEntity secondChunk = new KnowledgeChunkEntity();
        secondChunk.setDocumentId(2L);
        secondChunk.setChunkIndex(0);
        secondChunk.setContent("second");
        when(chunks.findByDocumentIdIn(List.of(1L, 2L))).thenReturn(List.of(firstChunk, secondChunk));

        KnowledgeDocumentEntity firstDocument = new KnowledgeDocumentEntity();
        ReflectionTestUtils.setField(firstDocument, "id", 1L);
        firstDocument.setFileName("first.pdf");
        KnowledgeDocumentEntity secondDocument = new KnowledgeDocumentEntity();
        ReflectionTestUtils.setField(secondDocument, "id", 2L);
        secondDocument.setFileName("second.pdf");
        when(documents.findAllById(List.of(1L, 2L))).thenReturn(List.of(firstDocument, secondDocument));
        when(rerank.rerank(eq("query"), anyList())).thenReturn(RerankResult.reranked(List.of(
                new RankedChunk(2L, 0, "second", 0.95f, null),
                new RankedChunk(1L, 0, "first", 0.85f, null))));

        List<Map<String, Object>> results = service.search(
                2L, List.of(11L, 12L), "query", List.of(), 5, 0.8f);

        assertThat(results).extracting(row -> row.get("docName"))
                .containsExactly("second.pdf", "first.pdf");
        verify(rewrite, times(1)).rewrite("query", List.of());
        verify(embedding, times(1)).embedBatch(List.of("query-a", "query-b"));
        verify(vectorStore, times(2)).search(eq(2L), eq(11L), any(float[].class), eq(20));
        verify(vectorStore, times(2)).search(eq(2L), eq(12L), any(float[].class), eq(20));
        verify(rerank, times(1)).rerank(eq("query"), anyList());
    }
}
