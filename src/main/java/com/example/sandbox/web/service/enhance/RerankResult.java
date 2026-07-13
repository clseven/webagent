package com.example.sandbox.web.service.enhance;

import java.util.List;

/**
 * 一次重排调用的结构化结果。
 *
 * <p>除排序结果外显式记录分数是否来自成功的重排服务，使调用方能够只对
 * 重排模型分数应用相关度阈值，避免把未归一化的向量分数误当作模型分数。</p>
 *
 * @param chunks 已按相关度降序排列的结果
 * @param reranked true 表示分数来自成功的重排服务，false 表示按向量分数降级
 */
public record RerankResult(List<RankedChunk> chunks, boolean reranked) {

    /**
     * 规范化重排结果，确保结果列表始终非空且不可变。
     */
    public RerankResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    /**
     * 创建成功重排结果。
     *
     * @param chunks 重排服务返回的结果
     * @return 标记为已重排的结构化结果
     */
    public static RerankResult reranked(List<RankedChunk> chunks) {
        return new RerankResult(chunks, true);
    }

    /**
     * 创建向量分数降级结果。
     *
     * @param chunks 按向量分数排序的结果
     * @return 标记为未重排的结构化结果
     */
    public static RerankResult vectorFallback(List<RankedChunk> chunks) {
        return new RerankResult(chunks, false);
    }
}
