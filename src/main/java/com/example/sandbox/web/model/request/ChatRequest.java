package com.example.sandbox.web.model.request;

/**
 * 对话请求
 *
 * @author example
 * @date 2026/05/14
 */
public class ChatRequest {

    /**
     * 用户消息内容
     */
    private String message;

    /**
     * 是否启用网络搜索（前端开关）
     */
    private boolean searchEnabled;

    /**
     * 是否启用规划模型（前端开关），默认开启以保持复杂任务的既有行为。
     */
    private boolean planningEnabled = true;

    /**
     * 是否启用知识库检索（前端开关），默认开启以兼容既有自动增强行为。
     */
    private boolean knowledgeEnabled = true;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isSearchEnabled() {
        return searchEnabled;
    }

    public void setSearchEnabled(boolean searchEnabled) {
        this.searchEnabled = searchEnabled;
    }

    public boolean isPlanningEnabled() {
        return planningEnabled;
    }

    public void setPlanningEnabled(boolean planningEnabled) {
        this.planningEnabled = planningEnabled;
    }

    /**
     * 获取本轮知识库开关状态。
     *
     * @return true 表示检索当前 Agent 关联的全部知识库
     */
    public boolean isKnowledgeEnabled() {
        return knowledgeEnabled;
    }

    /**
     * 设置本轮知识库开关状态。
     *
     * @param knowledgeEnabled 是否启用知识库
     */
    public void setKnowledgeEnabled(boolean knowledgeEnabled) {
        this.knowledgeEnabled = knowledgeEnabled;
    }
}
