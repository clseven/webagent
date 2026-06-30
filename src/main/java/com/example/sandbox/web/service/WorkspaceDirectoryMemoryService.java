package com.example.sandbox.web.service;

/**
 * 沙箱工作区目录记忆服务。
 *
 * <p>该服务只维护前端工作区面板可见的目录结构元数据，不读取或保存文件正文。</p>
 */
public interface WorkspaceDirectoryMemoryService {

    /**
     * 刷新当前用户在指定会话中的工作区目录记忆。
     *
     * @param sessionId 会话 ID
     */
    void refresh(String sessionId);

    /**
     * 构建可注入 Agent 上下文的目录摘要。
     *
     * @param sessionId 会话 ID
     * @return 目录摘要；没有可见目录记忆时返回空字符串
     */
    String buildContext(String sessionId);
}
