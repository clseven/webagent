package com.example.sandbox.web.controller;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.response.AgentAppResponse;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.AgentAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 应用控制器
 *
 * @author example
 * @date 2026/06/02
 */
@RestController
@RequestMapping("/api/apps")
public class AgentAppController {

    private static final Logger log = LoggerFactory.getLogger(AgentAppController.class);

    @Autowired
    private AgentAppService agentAppService;

    /**
     * 创建应用
     */
    @PostMapping
    public ApiResponse<AgentAppResponse> createApp(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getCurrentUserId();
        String name = body.get("name");
        String description = body.get("description");
        log.info("创建 Agent 应用: userId={}, name={}", userId, name);
        AgentAppEntity app = agentAppService.createApp(userId, name, description);
        return ApiResponse.success(AgentAppResponse.from(app));
    }

    /**
     * 列出用户的应用
     */
    @GetMapping
    public ApiResponse<List<AgentAppResponse>> listApps() {
        Long userId = UserContext.getCurrentUserId();
        List<AgentAppResponse> apps = agentAppService.listApps(userId)
                .stream()
                .map(AgentAppResponse::from)
                .collect(Collectors.toList());
        return ApiResponse.success(apps);
    }

    /**
     * 获取应用详情
     */
    @GetMapping("/{appId}")
    public ApiResponse<AgentAppResponse> getApp(@PathVariable Long appId) {
        AgentAppEntity app = agentAppService.getApp(appId);
        return ApiResponse.success(AgentAppResponse.from(app));
    }

    /**
     * 更新应用
     */
    @PutMapping("/{appId}")
    public ApiResponse<AgentAppResponse> updateApp(
            @PathVariable Long appId, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String description = body.get("description");
        log.info("更新 Agent 应用: appId={}", appId);
        AgentAppEntity app = agentAppService.updateApp(appId, name, description);
        return ApiResponse.success(AgentAppResponse.from(app));
    }

    /**
     * 删除应用
     */
    @DeleteMapping("/{appId}")
    public ApiResponse<Void> deleteApp(@PathVariable Long appId) {
        log.info("删除 Agent 应用: appId={}", appId);
        agentAppService.deleteApp(appId);
        return ApiResponse.success();
    }

    /**
     * 更新应用关联的知识库
     */
    @PutMapping("/{appId}/knowledge-bases")
    public ApiResponse<Void> updateKnowledgeBases(
            @PathVariable Long appId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Set<Long> kbIds = ((List<Number>) body.get("kbIds"))
                .stream()
                .map(Number::longValue)
                .collect(Collectors.toSet());
        log.info("更新应用知识库: appId={}, kbIds={}", appId, kbIds);
        agentAppService.updateKnowledgeBases(appId, kbIds);
        return ApiResponse.success();
    }

    /**
     * 更新应用关联的 Skill
     */
    @PutMapping("/{appId}/skills")
    public ApiResponse<Void> updateSkills(
            @PathVariable Long appId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Set<String> skillIds = ((List<String>) body.get("skillIds"))
                .stream()
                .collect(Collectors.toSet());
        log.info("更新应用 Skill: appId={}, skillIds={}", appId, skillIds);
        agentAppService.updateSkills(appId, skillIds);
        return ApiResponse.success();
    }
}
