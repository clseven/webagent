package com.example.sandbox.web.service.enhance;

import java.util.List;

/**
 * 重排结果统一过滤器。
 *
 * <p>负责在所有知识库入口中统一限制最终条数。只有分数来自成功的重排服务时
 * 才执行最低相关度过滤；向量降级分数保持原值，不与模型分数共用阈值。</p>
 */
public final class RerankResultFilter {

    /**
     * 工具类不允许实例化。
     */
    private RerankResultFilter() {
    }

    /**
     * 按最低相关度过滤重排结果，并限制最终条数。
     *
     * @param result 结构化重排结果
     * @param minScore 最低相关度，超出 0 到 1 时会被收敛到合法范围
     * @param topK 最大返回条数；小于零时按零处理
     * @return 保持原顺序的合格结果
     */
    public static List<RankedChunk> filter(RerankResult result, float minScore, int topK) {
        if (result == null || result.chunks().isEmpty() || topK <= 0) {
            return List.of();
        }
        if (!result.reranked()) {
            return result.chunks().stream().limit(topK).toList();
        }
        float safeMinScore = Math.max(0.0f, Math.min(1.0f, minScore));
        return result.chunks().stream()
                .filter(chunk -> chunk.score() >= safeMinScore)
                .limit(topK)
                .toList();
    }
}
