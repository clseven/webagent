package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.TokenUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 用量数据访问
 */
@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, Long> {

    /**
     * 查询用户在指定时间范围内的所有记录
     */
    List<TokenUsageEntity> findByUserIdAndCreatedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    /**
     * 按日期汇总用户的 token 用量
     */
    @Query(value = """
            SELECT DATE(created_at) as date,
                   SUM(prompt_tokens) as prompt,
                   SUM(completion_tokens) as completion,
                   SUM(cache_hit_tokens) as cache_hit,
                   SUM(total_tokens) as total
            FROM token_usage
            WHERE user_id = :userId AND created_at >= :since
            GROUP BY DATE(created_at)
            ORDER BY date
            """, nativeQuery = true)
    List<Object[]> aggregateDailyByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * 按模型汇总用户的 token 用量
     */
    @Query(value = """
            SELECT model, SUM(total_tokens) as total
            FROM token_usage
            WHERE user_id = :userId AND created_at >= :since
            GROUP BY model
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> aggregateByModel(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * 汇总用户的总 token 用量
     */
    @Query(value = """
            SELECT COALESCE(SUM(prompt_tokens), 0),
                   COALESCE(SUM(completion_tokens), 0),
                   COALESCE(SUM(cache_hit_tokens), 0),
                   COALESCE(SUM(total_tokens), 0)
            FROM token_usage
            WHERE user_id = :userId AND created_at >= :since
            """, nativeQuery = true)
    List<Object[]> aggregateTotalByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
