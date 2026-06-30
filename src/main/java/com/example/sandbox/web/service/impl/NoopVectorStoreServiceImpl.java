package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Milvus 关闭时的向量存储空实现。
 *
 * <h3>用途</h3>
 * <p>当本地开发或轻量启动不需要向量检索时，避免应用上下文创建 MilvusClient，
 * 同时让知识库上传、删除等上层流程仍然可以完成。</p>
 *
 * <h3>注意</h3>
 * <p>该实现不会写入或删除任何向量，检索固定返回空结果；需要 RAG 检索时必须重新启用 Milvus。</p>
 */
@Service
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "false")
public class NoopVectorStoreServiceImpl implements VectorStoreService {

    /** 用于记录 Milvus 关闭后的降级行为。 */
    private static final Logger log = LoggerFactory.getLogger(NoopVectorStoreServiceImpl.class);

    /**
     * 跳过单条向量写入。
     *
     * @param userId     用户 ID
     * @param kbId       知识库 ID
     * @param docId      文档 ID
     * @param chunkIndex 切片序号
     * @param embedding  向量数据
     */
    @Override
    public void insert(Long userId, Long kbId, Long docId, int chunkIndex, float[] embedding) {
        log.info("Milvus 已关闭，跳过单条向量写入: userId={}, kbId={}, docId={}, chunkIndex={}",
                userId, kbId, docId, chunkIndex);
    }

    /**
     * 跳过批量向量写入。
     *
     * @param userId     用户 ID
     * @param kbId       知识库 ID
     * @param docId      文档 ID
     * @param embeddings 向量列表
     */
    @Override
    public void insertBatch(Long userId, Long kbId, Long docId, List<Map<String, Object>> embeddings) {
        int count = embeddings != null ? embeddings.size() : 0;
        log.info("Milvus 已关闭，跳过批量向量写入: userId={}, kbId={}, docId={}, count={}",
                userId, kbId, docId, count);
    }

    /**
     * 返回空检索结果。
     *
     * @param userId         用户 ID
     * @param kbId           知识库 ID
     * @param queryEmbedding 查询向量
     * @param topK           返回数量
     * @return 固定为空列表，表示没有可用向量召回结果
     */
    @Override
    public List<Map<String, Object>> search(Long userId, Long kbId, float[] queryEmbedding, int topK) {
        log.info("Milvus 已关闭，向量检索返回空结果: userId={}, kbId={}, topK={}", userId, kbId, topK);
        return List.of();
    }

    /**
     * 跳过按文档删除向量。
     *
     * @param docId 文档 ID
     */
    @Override
    public void deleteByDocId(Long docId) {
        log.info("Milvus 已关闭，跳过文档向量删除: docId={}", docId);
    }

    /**
     * 跳过按用户删除向量。
     *
     * @param userId 用户 ID
     */
    @Override
    public void deleteByUserId(Long userId) {
        log.info("Milvus 已关闭，跳过用户向量删除: userId={}", userId);
    }

    /**
     * 跳过按知识库删除向量。
     *
     * @param kbId 知识库 ID
     */
    @Override
    public void deleteByKbId(Long kbId) {
        log.info("Milvus 已关闭，跳过知识库向量删除: kbId={}", kbId);
    }
}
