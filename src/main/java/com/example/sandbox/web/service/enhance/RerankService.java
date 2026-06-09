package com.example.sandbox.web.service.enhance;

import java.util.List;

/**
 * Rerank 服务接口
 *
 * <p>对候选文档片段进行相关性重排，把真正相关的片段排到前面。</p>
 *
 * @author example
 * @date 2026/06/05
 */
public interface RerankService {

    /**
     * 对候选片段重排
     *
     * @param query 原始查询
     * @param candidates 候选片段列表（每个片段含 content）
     * @return 重排后的片段列表（按相关性降序），每个片段含相关性分数
     */
    List<RankedChunk> rerank(String query, List<RawChunk> candidates);
}
