package com.example.sandbox.web.service;

/**
 * 沙箱客户端接口
 *
 * <p>统一 opensandbox 和 AIO 两套沙箱的操作 API，
 * 工具层只需依赖此接口，无需关心具体实现。</p>
 *
 * @author example
 * @date 2026/05/20
 */
public interface SandboxClient {

    /**
     * 在沙箱中执行 shell 命令
     *
     * @param command 要执行的命令
     * @return 命令输出
     */
    String execCommand(String command);

    /**
     * 读取沙箱中的文件
     *
     * @param path 文件路径
     * @return 文件内容
     */
    String readFile(String path);

    /**
     * 写入文件到沙箱
     *
     * @param path    文件路径
     * @param content 文件内容
     */
    void writeFile(String path, String content);

    /**
     * 下载文件（部分沙箱支持）
     *
     * @param path 文件路径
     * @return 文件内容
     */
    byte[] downloadFile(String path);

    /**
     * 截取浏览器截图（部分沙箱支持）
     *
     * @return 截图数据（PNG）
     */
    byte[] screenshot();

    /**
     * 获取沙箱环境信息（部分沙箱支持）
     *
     * @return 环境信息
     */
    SandboxContext getContext();

    /**
     * 检查沙箱是否就绪
     *
     * @return 是否就绪
     */
    boolean isReady();

    /**
     * 沙箱环境信息
     */
    class SandboxContext {
        private String homeDir;
        private String workspace;

        public String getHomeDir() { return homeDir; }
        public void setHomeDir(String homeDir) { this.homeDir = homeDir; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }

        @Override
        public String toString() {
            return "SandboxContext{homeDir='" + homeDir + "', workspace='" + workspace + "'}";
        }
    }
}