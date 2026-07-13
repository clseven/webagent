package com.example.sandbox.web.context;

import com.example.sandbox.web.exception.UnauthorizedException;

public class UserContext {

    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> webSearchEnabled = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> planningEnabled = new ThreadLocal<>();
    /** 当前请求是否启用知识库检索；使用请求线程隔离。 */
    private static final ThreadLocal<Boolean> knowledgeEnabled = new ThreadLocal<>();

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

    /** 设置当前请求是否启用网络搜索 */
    public static void setWebSearchEnabled(boolean enabled) {
        webSearchEnabled.set(enabled);
    }

    /** 获取当前请求的网络搜索开关状态，未设置时默认 false */
    public static boolean isWebSearchEnabled() {
        return Boolean.TRUE.equals(webSearchEnabled.get());
    }

    /** 设置当前请求是否启用规划模型 */
    public static void setPlanningEnabled(boolean enabled) {
        planningEnabled.set(enabled);
    }

    /** 获取当前请求的规划模型开关状态，未设置时默认 true */
    public static boolean isPlanningEnabled() {
        return !Boolean.FALSE.equals(planningEnabled.get());
    }

    /**
     * 设置当前请求是否启用知识库检索。
     *
     * @param enabled true 表示本轮必须检索 Agent 关联的全部知识库
     */
    public static void setKnowledgeEnabled(boolean enabled) {
        knowledgeEnabled.set(enabled);
    }

    /**
     * 获取当前请求的知识库开关状态，未设置时默认开启以兼容旧客户端。
     *
     * @return 是否启用知识库检索
     */
    public static boolean isKnowledgeEnabled() {
        return !Boolean.FALSE.equals(knowledgeEnabled.get());
    }

    public static void clear() {
        currentUserId.remove();
        webSearchEnabled.remove();
        planningEnabled.remove();
        knowledgeEnabled.remove();
    }
}
