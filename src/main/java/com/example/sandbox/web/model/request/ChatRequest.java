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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
