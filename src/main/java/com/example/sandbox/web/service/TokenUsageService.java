package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.TokenUsageEntity;
import com.example.sandbox.web.repository.TokenUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Token 用量统计服务
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    @Autowired
    private TokenUsageRepository repository;

    /**
     * 记录一次 token 消耗
     */
    public void record(Long userId, String sessionId, int promptTokens, int completionTokens,
                       int cacheHitTokens, int totalTokens, String model, String messageType) {
        try {
            TokenUsageEntity entity = new TokenUsageEntity(
                    userId, sessionId, promptTokens, completionTokens,
                    cacheHitTokens, totalTokens, model, messageType
            );
            repository.save(entity);
            log.debug("Token 记录已保存: userId={}, total={}", userId, totalTokens);
        } catch (Exception e) {
            log.warn("保存 Token 记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取用户指定天数的每日统计数据
     */
    public List<Map<String, Object>> getDailyStats(Long userId, int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = repository.aggregateDailyByUser(userId, since);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row[0].toString());
            item.put("promptTokens", ((Number) row[1]).longValue());
            item.put("completionTokens", ((Number) row[2]).longValue());
            item.put("cacheHitTokens", ((Number) row[3]).longValue());
            item.put("totalTokens", ((Number) row[4]).longValue());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取用户按模型的统计数据
     */
    public List<Map<String, Object>> getModelStats(Long userId, int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = repository.aggregateByModel(userId, since);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", row[0] != null ? row[0].toString() : "unknown");
            item.put("totalTokens", ((Number) row[1]).longValue());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取用户的汇总统计
     */
    public Map<String, Object> getSummary(Long userId, int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = repository.aggregateTotalByUser(userId, since);

        Map<String, Object> result = new LinkedHashMap<>();

        long promptTokens = 0L;
        long completionTokens = 0L;
        long cacheHitTokens = 0L;
        long totalTokens = 0L;

        if (rows != null && !rows.isEmpty()) {
            Object[] row = rows.get(0);
            promptTokens = row.length > 0 ? toLong(row[0]) : 0L;
            completionTokens = row.length > 1 ? toLong(row[1]) : 0L;
            cacheHitTokens = row.length > 2 ? toLong(row[2]) : 0L;
            totalTokens = row.length > 3 ? toLong(row[3]) : 0L;
        }

        result.put("promptTokens", promptTokens);
        result.put("completionTokens", completionTokens);
        result.put("cacheHitTokens", cacheHitTokens);
        result.put("totalTokens", totalTokens);
        result.put("cacheHitRate", totalTokens > 0 ? Math.round(cacheHitTokens * 100.0 / totalTokens) : 0);

        return result;
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
