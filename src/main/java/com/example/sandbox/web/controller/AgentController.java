package com.example.sandbox.web.controller;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.request.BatchDeleteSessionsRequest;
import com.example.sandbox.web.model.request.ChatRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.model.response.BatchDeleteSessionsResponse;
import com.example.sandbox.web.model.response.SessionResponse;
import com.example.sandbox.web.model.response.SkillView;
import com.example.sandbox.web.model.sse.SseEvent;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.impl.ConversationServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 会话及对话 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/sessions")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationServiceImpl conversationServiceImpl;

    /**
     * 列出当前用户的所有会话
     */
    @GetMapping
    public ApiResponse<List<SessionResponse>> listSessions() {
        Long userId = UserContext.getCurrentUserId();
        List<ConversationSession> sessions = conversationServiceImpl.listUserSessions(userId);
        List<SessionResponse> responses = sessions.stream()
                .map(this::toSessionResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    /**
     * 创建会话
     */
    @PostMapping
    public ApiResponse<SessionResponse> createSession(@RequestBody(required = false) Map<String, Object> body) {
        Long appId = null;
        if (body != null && body.containsKey("appId")) {
            appId = ((Number) body.get("appId")).longValue();
        }
        ConversationSession session = (appId != null)
                ? agentService.createSession(appId)
                : agentService.createSession();
        return ApiResponse.success(toSessionResponse(session));
    }

    /**
     * 删除当前用户拥有的会话及其历史消息。
     *
     * @param id 会话 ID
     * @return 删除成功时返回空响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable String id) {
        agentService.deleteSession(id);
        return ApiResponse.success();
    }

    /**
     * 批量删除当前用户拥有的会话及其历史消息。
     *
     * @param request 待删除的会话 ID 列表
     * @return 实际删除和跳过的会话 ID
     */
    @DeleteMapping("/batch")
    public ApiResponse<BatchDeleteSessionsResponse> deleteSessions(
            @RequestBody BatchDeleteSessionsRequest request) {
        List<String> sessionIds = request == null ? List.of() : request.getSessionIds();
        return ApiResponse.success(agentService.deleteSessions(sessionIds));
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/{id}")
    public ApiResponse<SessionResponse> getSession(@PathVariable String id) {
        ConversationSession session = agentService.getSession(id);
        return ApiResponse.success(toSessionResponse(session));
    }

    /**
     * 发送消息
     */
    @PostMapping("/{id}/chat")
    public ApiResponse<ChatMessage> chat(@PathVariable String id, @RequestBody ChatRequest request) {
        UserContext.setWebSearchEnabled(request.isSearchEnabled());
        UserContext.setPlanningEnabled(request.isPlanningEnabled());
        try {
            ChatMessage response = agentService.chat(id, request.getMessage());
            return ApiResponse.success(response);
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 流式对话（SSE）
     *
     * <p>实时返回思考过程、工具调用、最终答案等事件。</p>
     * <p>客户端可通过关闭连接中断执行。</p>
     *
     * @param id      会话 ID
     * @param message 用户消息
     * @return SSE 事件流
     */
    @GetMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @PathVariable String id,
            @RequestParam String message,
            @RequestParam(defaultValue = "false") boolean searchEnabled,
            @RequestParam(defaultValue = "true") boolean planningEnabled,
            HttpServletResponse response) {

        Long userId = UserContext.getCurrentUserId();
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(0L);
        try {
            // 先提交一个注释帧，确保浏览器立即进入 SSE 读取状态。
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            emitter.completeWithError(e);
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                UserContext.setCurrentUserId(userId);
                UserContext.setWebSearchEnabled(searchEnabled);
                UserContext.setPlanningEnabled(planningEnabled);
                agentService.chatStream(id, message)
                        .doFinally(signalType -> UserContext.clear())
                        .subscribe(
                                event -> sendSseEvent(emitter, event),
                                emitter::completeWithError,
                                emitter::complete
                        );
            } catch (Exception e) {
                UserContext.clear();
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 逐条发送 SSE 事件。
     *
     * <p>Servlet 环境下直接返回 Flux 可能被容器或过滤链缓冲，导致前端只能在任务结束后看到整批步骤。
     * 这里使用 SseEmitter 每收到一个 Agent 事件就写出一个具名 SSE 帧，让工具调用步骤可以实时显示。</p>
     *
     * @param emitter 当前 HTTP 连接对应的 SSE 发射器
     * @param event   后端执行阶段产生的单个流式事件
     * @throws IllegalStateException 当连接已断开或写出失败时抛出，交由订阅错误分支结束流
     */
    private void sendSseEvent(SseEmitter emitter, SseEvent event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(event));
            }
        } catch (Exception e) {
            throw new IllegalStateException("发送 SSE 事件失败: " + event.type(), e);
        }
    }

    /**
     * 获取历史消息
     */
    @GetMapping("/{id}/history")
    public ApiResponse<List<ChatMessage>> getHistory(@PathVariable String id) {
        List<ChatMessage> history = conversationService.getHistory(id);
        return ApiResponse.success(history);
    }

    /**
     * 获取启用的技能 ID 集合（仅 ID，旧接口）。
     */
    @GetMapping("/{id}/skills")
    public ApiResponse<Set<String>> getEnabledSkills(@PathVariable String id) {
        Set<String> skills = conversationService.getEnabledSkillIds(id);
        return ApiResponse.success(skills);
    }

    /**
     * 列出当前会话可见的技能融合视图：本地仓库 ∪ 当前会话沙箱发现，
     * 每项带 source（local / sandbox / both）与 enabled 标记。
     */
    @GetMapping("/{id}/skills/available")
    public ApiResponse<List<SkillView>> listSessionSkills(@PathVariable String id) {
        List<SkillView> skills = conversationService.listSessionSkills(id);
        return ApiResponse.success(skills);
    }

    /**
     * 启用技能
     */
    @PostMapping("/{id}/skills/{skillId}/enable")
    public ApiResponse<Void> enableSkill(@PathVariable String id, @PathVariable String skillId) {
        conversationService.enableSkill(id, skillId);
        return ApiResponse.success();
    }

    /**
     * 禁用技能
     */
    @PostMapping("/{id}/skills/{skillId}/disable")
    public ApiResponse<Void> disableSkill(@PathVariable String id, @PathVariable String skillId) {
        conversationService.disableSkill(id, skillId);
        return ApiResponse.success();
    }

    private SessionResponse toSessionResponse(ConversationSession session) {
        SessionResponse response = new SessionResponse();
        response.setSessionId(session.getSessionId());
        response.setTitle(session.getTitle());
        response.setSandboxId(session.getSandboxId());
        response.setAppId(session.getAppId());
        response.setEnabledSkillIds(session.getEnabledSkillIds());
        response.setCreatedAt(session.getCreatedAt());
        response.setUpdatedAt(session.getUpdatedAt());
        return response;
    }
}
