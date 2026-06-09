package com.example.sandbox.web.service;

import java.io.InputStream;

/**
 * 文件存储服务接口
 * 支持本地存储和 OSS 存储，通过DelegatingFileStorageService动态选择
 *
 * @author example
 * @date 2026/05/20
 */
public interface FileStorageService {

    /**
     * 存储文件
     *
     * @param sessionId         会话 ID（用于目录隔离）
     * @param originalFilename  原始文件名
     * @param inputStream       文件流
     * @return 存储后的相对路径（相对于 base-path）
     */
    String store(String sessionId, String originalFilename, InputStream inputStream);

    /**
     * 存储文件（字节数组）
     *
     * @param sessionId         会话 ID
     * @param originalFilename  原始文件名
     * @param data              文件数据
     * @return 存储后的相对路径
     */
    String store(String sessionId, String originalFilename, byte[] data);

    /**
     * 获取文件
     *
     * @param sessionId 会话 ID
     * @param filename   文件名
     * @return 文件输入流
     */
    InputStream getFile(String sessionId, String filename);

    /**
     * 删除文件
     *
     * @param sessionId 会话 ID
     * @param filename   文件名
     */
    void delete(String sessionId, String filename);

    /**
     * 删除会话目录下的所有文件
     *
     * @param sessionId 会话 ID
     */
    void deleteAll(String sessionId);

    /**
     * 获取存储路径（供沙盒挂载用）
     *
     * @param sessionId 会话 ID
     * @return 宿主机上的存储目录绝对路径
     */
    String getStoragePath(String sessionId);

    /**
     * 获取沙盒内的挂载路径
     *
     * @return 容器内的挂载路径
     */
    String getMountPath();

    /**
     * 检查存储类型
     *
     * @return "local" 或 "oss"
     */
    String getType();
}
