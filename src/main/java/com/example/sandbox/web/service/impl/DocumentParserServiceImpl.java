package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.service.DocumentParserService;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;

/**
 * 文档解析服务实现（基于 Apache Tika）
 * 支持 PDF/DOCX/PPTX/XLS/HTML/图片等 700+ 格式
 *
 * @author example
 * @date 2026/05/31
 */
@Service
public class DocumentParserServiceImpl implements DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserServiceImpl.class);

    private final Tika tika = new Tika();

    @Override
    public String parse(String filePath) {
        try {
            log.info("解析文件: {}", filePath);
            String content = tika.parseToString(new File(filePath));
            log.info("文件解析完成, 文本长度: {}", content.length());
            return content;
        } catch (Exception e) {
            log.error("文件解析失败: {}", filePath, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String parse(byte[] data, String fileName) {
        try {
            log.info("解析文件: {}, 大小: {} bytes", fileName, data.length);
            try (InputStream stream = new ByteArrayInputStream(data)) {
                String content = tika.parseToString(stream);
                log.info("文件解析完成, 文本长度: {}", content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("文件解析失败: {}", fileName, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        }
    }
}
