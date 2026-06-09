package com.example.sandbox.web.exception;

/**
 * 会话不存在异常
 *
 * @author example
 * @date 2026/05/14
 */
public class SessionNotFoundException extends RuntimeException {

    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}