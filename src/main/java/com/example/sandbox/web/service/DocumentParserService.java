package com.example.sandbox.web.service;

/**
 * 文档解析服务接口
 *
 * @author example
 * @date 2026/05/31
 */
public interface DocumentParserService {

    /**
     * 解析文件为纯文本
     *
     * @param filePath 文件路径
     * @return 提取的文本内容
     */
    String parse(String filePath);

    /**
     * 解析字节数组为纯文本
     *
     * @param data     文件字节数组
     * @param fileName 文件名（用于判断格式）
     * @return 提取的文本内容
     */
    String parse(byte[] data, String fileName);
}
