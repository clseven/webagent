package com.example.sandbox.web.service;

import java.util.List;
import java.util.Map;

/**
 * 向量存储服务接口
 *
 * @author example
 * @date 2026/05/31
 */
public interface VectorStoreService {

    /**
     * 存储单条向量
     *
     * @param userId     用户 ID
     * @param kbId       知识库 ID
     * @param docId      文档 ID
     * @param chunkIndex 切片序号
     * @param embedding  向量数据
     */
    void insert(Long userId, Long kbId, Long docId, int chunkIndex, float[] embedding);

    /**
     * 批量存储向量
     *
     * @param userId     用户 ID
     * @param kbId       知识库 ID
     * @param docId      文档 ID
     * @param embeddings 向量列表（每项包含 chunkIndex 和 embedding）
     */
    void insertBatch(Long userId, Long kbId, Long docId, List<Map<String, Object>> embeddings);

    /**
     * 向量检索
     *
     * @param userId         用户 ID
     * @param kbId           知识库 ID（可选，为 null 时检索该用户所有知识库）
     * @param queryEmbedding 查询向量
     * @param topK           返回数量
     * @return 检索结果列表，每项包含 docId、chunkIndex、score
     */
    List<Map<String, Object>> search(Long userId, Long kbId, float[] queryEmbedding, int topK);

    /**
     * 删除文档的所有向量
     *
     * @param docId 文档 ID
     */
    void deleteByDocId(Long docId);

    /**
     * 删除用户的所有向量
     *
     * @param userId 用户 ID
     */
    void deleteByUserId(Long userId);

    /**
     * 删除知识库的所有向量
     *
     * @param kbId 知识库 ID
     */
    void deleteByKbId(Long kbId);
}
