package com.example.sandbox.web.model.response;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 会话响应
 *
 * @author example
 * @date 2026/05/14
 */
public class SessionResponse {

    private String sessionId;
    /**
     * 会话列表展示标题。
     */
    private String title;
    private String sandboxId;
    private Long appId;
    private Set<String> enabledSkillIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 获取会话列表展示标题。
     *
     * @return 当前会话标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置会话列表展示标题。
     *
     * @param title 当前会话标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public Set<String> getEnabledSkillIds() {
        return enabledSkillIds;
    }

    public void setEnabledSkillIds(Set<String> enabledSkillIds) {
        this.enabledSkillIds = enabledSkillIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
