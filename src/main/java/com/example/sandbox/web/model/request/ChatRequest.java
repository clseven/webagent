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
}
