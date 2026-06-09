package com.example.sandbox.web.controller;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.TokenUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Token 用量统计 API
 */
@RestController
@RequestMapping("/api/token-stats")
public class TokenStatsController {

    @Autowired
    private TokenUsageService tokenUsageService;

    /**
     * 获取汇总统计
     * @param days 统计天数，默认30
     */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSummary(@RequestParam(defaultValue = "30") int days) {
        Long userId = UserContext.getCurrentUserId();
        Map<String, Object> summary = tokenUsageService.getSummary(userId, days);
        return ApiResponse.success(summary);
    }

    /**
     * 获取每日统计数据
     * @param days 统计天数，默认7
     */
    @GetMapping("/daily")
    public ApiResponse<List<Map<String, Object>>> getDailyStats(@RequestParam(defaultValue = "7") int days) {
        Long userId = UserContext.getCurrentUserId();
        List<Map<String, Object>> daily = tokenUsageService.getDailyStats(userId, days);
        return ApiResponse.success(daily);
    }

    /**
     * 获取按模型的统计数据
     * @param days 统计天数，默认30
     */
    @GetMapping("/by-model")
    public ApiResponse<List<Map<String, Object>>> getModelStats(@RequestParam(defaultValue = "30") int days) {
        Long userId = UserContext.getCurrentUserId();
        List<Map<String, Object>> byModel = tokenUsageService.getModelStats(userId, days);
        return ApiResponse.success(byModel);
    }
}
