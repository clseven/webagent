package com.example.sandbox.web.context;

import com.example.sandbox.web.exception.UnauthorizedException;

public class UserContext {

    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();

    public static void setCurrentUserId(Long userId) {
        currentUserId.set(userId);
    }

    public static Long getCurrentUserId() {
        Long userId = currentUserId.get();
        if (userId == null) {
            throw new UnauthorizedException();
        }
        return userId;
    }

    public static void clear() {
        currentUserId.remove();
    }
}
