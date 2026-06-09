package com.example.sandbox.web.service.enhance;

/**
 * 原始检索候选片段
 *
 * @param docId 文档 ID
 * @param chunkIndex 片段索引
 * @param content 片段内容
 * @param score 向量检索分数
 *
 * @author example
 * @date 2026/06/05
 */
public record RawChunk(
    Long docId,
    int chunkIndex,
    String content,
    float score
) {}
