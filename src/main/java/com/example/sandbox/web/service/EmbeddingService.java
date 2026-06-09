package com.example.sandbox.web.service;

import java.util.List;

/**
 * 向量化服务接口
 *
 * @author example
 * @date 2026/05/31
 */
public interface EmbeddingService {

    /**
     * Embedding 结果
     */
    record EmbeddingResult(List<float[]> embeddings, int promptTokens) {}

    /**
     * 批量向量化
     *
     * @param texts 文本列表
     * @return Embedding 结果（包含向量和 token 用量）
     */
    EmbeddingResult embedBatch(List<String> texts);
}
