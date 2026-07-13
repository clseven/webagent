package com.example.sandbox.web.service.enhance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证重排结果的统一最低相关度过滤规则。
 */
class RerankResultFilterTest {

    /**
     * 验证先丢弃低分结果，再限制最终返回条数。
     */
    @Test
    void filtersByMinimumScoreBeforeApplyingTopK() {
        List<RankedChunk> filtered = RerankResultFilter.filter(RerankResult.reranked(List.of(
                new RankedChunk(1L, 0, "perfect", 1.0f, null),
                new RankedChunk(1L, 1, "good", 0.85f, null),
                new RankedChunk(1L, 2, "weak", 0.3f, null))), 0.8f, 1);

        assertThat(filtered).extracting(RankedChunk::score).containsExactly(1.0f);
    }

    /**
     * 验证向量降级分数不套用模型相关度阈值，只限制最终数量。
     */
    @Test
    void doesNotApplyModelThresholdToVectorFallback() {
        List<RankedChunk> filtered = RerankResultFilter.filter(RerankResult.vectorFallback(List.of(
                new RankedChunk(1L, 0, "vector", 0.3f, null))), 0.8f, 5);

        assertThat(filtered).extracting(RankedChunk::score).containsExactly(0.3f);
    }
}
