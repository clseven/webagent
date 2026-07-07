package com.example.sandbox.web.service;

/**
 * 沙盒操作服务接口
 *
 * <p>统一使用 AIO 模式，工具调用通过分领域的 AioClient 进行。</p>
 *
 * @author example
 * @date 2026/05/14
 */
public interface SandboxService {

    /**
     * 创建沙箱（按需创建）
     *
     * @param sessionId 会话 ID
     */
    void createSandbox(String sessionId);

    /**
     * 重置当前会话所属用户的沙箱。
     *
     * <p>会清理旧的用户级沙箱绑定，并为当前会话重新创建沙箱。</p>
     *
     * @param sessionId 会话 ID
     */
    void resetSandbox(String sessionId);

    /**
     * 检查沙箱是否已创建
     *
     * @param sessionId 会话 ID
     * @return 是否已创建
     */
    boolean hasSandbox(String sessionId);

    /**
     * 判断会话使用的沙箱是否为 AIO 类型
     *
     * @param sessionId 会话 ID
     * @return true（统一返回 true）
     * @deprecated 已废弃，统一为 AIO 模式
     */
    @Deprecated
    boolean isAioSandbox(String sessionId);

    /**
     * 移除会话关联的沙箱（关闭并清理资源）
     *
     * @param sessionId 会话 ID
     */
    void removeSandbox(String sessionId);
}
