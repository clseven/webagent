package com.example.sandbox.web.service.enhance;

/**
 * Rerank 后的片段
 *
 * @param docId 文档 ID
 * @param chunkIndex 片段索引
 * @param content 片段内容
 * @param score Rerank 相关性分数（0-1）
 * @param docName 文档名称（可选，用于展示）
 *
 * @author example
 * @date 2026/06/05
 */
public record RankedChunk(
    Long docId,
    int chunkIndex,
    String content,
    float score,
    String docName
) {}
