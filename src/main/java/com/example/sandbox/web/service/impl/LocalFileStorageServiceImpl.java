package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * 本地文件存储服务实现
 *
 * @author example
 * @date 2026/05/20
 */
@Service
@Primary
public class LocalFileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageServiceImpl.class);

    private static final String MOUNT_PATH = "/home/gem/uploads";

    @Autowired
    private AgentConfigProperties config;

    @Override
    public String store(String sessionId, String originalFilename, InputStream inputStream) {
        Path sessionDir = getSessionDir(sessionId);
        String relativePath = sessionId + "/" + originalFilename;
        Path targetPath = sessionDir.resolve(originalFilename);

        try {
            Files.createDirectories(sessionDir);
            try (OutputStream out = Files.newOutputStream(targetPath)) {
                inputStream.transferTo(out);
            }
            log.info("文件存储成功: {}", relativePath);
            return relativePath;
        } catch (IOException e) {
            log.error("文件存储失败: {}", relativePath, e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String store(String sessionId, String originalFilename, byte[] data) {
        Path sessionDir = getSessionDir(sessionId);
        String relativePath = sessionId + "/" + originalFilename;
        Path targetPath = sessionDir.resolve(originalFilename);

        try {
            Files.createDirectories(sessionDir);
            Files.write(targetPath, data);
            log.info("文件存储成功: {}", relativePath);
            return relativePath;
        } catch (IOException e) {
            log.error("文件存储失败: {}", relativePath, e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getFile(String sessionId, String filename) {
        Path filePath = getSessionDir(sessionId).resolve(filename);
        try {
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            log.error("文件读取失败: {}", filePath, e);
            throw new RuntimeException("文件不存在: " + filename, e);
        }
    }

    @Override
    public void delete(String sessionId, String filename) {
        Path filePath = getSessionDir(sessionId).resolve(filename);
        try {
            Files.deleteIfExists(filePath);
            log.info("文件删除成功: {}", filePath);
        } catch (IOException e) {
            log.error("文件删除失败: {}", filePath, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteAll(String sessionId) {
        Path sessionDir = getSessionDir(sessionId);
        try {
            if (Files.exists(sessionDir)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(sessionDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                }
                log.info("会话目录删除成功: {}", sessionDir);
            }
        } catch (IOException e) {
            log.error("会话目录删除失败: {}", sessionDir, e);
            throw new RuntimeException("目录删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getStoragePath(String sessionId) {
        return getSessionDir(sessionId).toAbsolutePath().toString();
    }

    @Override
    public String getMountPath() {
        return MOUNT_PATH;
    }

    @Override
    public String getType() {
        return "local";
    }

    private Path getSessionDir(String sessionId) {
        String basePath = config.getStorage().getLocal().getBasePath();
        return Paths.get(basePath, sessionId);
    }
}
