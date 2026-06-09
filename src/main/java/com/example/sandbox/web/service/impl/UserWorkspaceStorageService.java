package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class UserWorkspaceStorageService {

    private static final String KNOWLEDGE_SANDBOX_ROOT = "/home/gem/knowledge";
    private static final String UPLOAD_SANDBOX_ROOT = "/home/gem/uploads";

    private final Path basePath;

    public UserWorkspaceStorageService(AgentConfigProperties properties) {
        this.basePath = Paths.get(properties.getStorage().getLocal().getBasePath())
                .toAbsolutePath()
                .normalize();
    }

    public Path knowledgeRoot(Long userId) {
        return userRoot(userId).resolve("knowledge");
    }

    public Path uploadRoot(Long userId) {
        return userRoot(userId).resolve("uploads");
    }

    public Path knowledgeFile(Long userId, Long kbId, String fileName) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        return knowledgeRoot(userId)
                .resolve(String.valueOf(kbId))
                .resolve(sanitizeFileName(fileName))
                .normalize();
    }

    public Path uploadFile(Long userId, String fileName) {
        return uploadRoot(userId)
                .resolve(sanitizeFileName(fileName))
                .normalize();
    }

    public String knowledgeSandboxPath(Long kbId, String fileName) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        return KNOWLEDGE_SANDBOX_ROOT + "/" + kbId + "/" + sanitizeFileName(fileName);
    }

    public String uploadSandboxPath(String fileName) {
        return UPLOAD_SANDBOX_ROOT + "/" + sanitizeFileName(fileName);
    }

    public Path write(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return target;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + target, e);
        }
    }

    public byte[] read(Path source) {
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败: " + source, e);
        }
    }

    public boolean exists(Path path) {
        return Files.isRegularFile(path);
    }

    public void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException("文件删除失败: " + path, e);
        }
    }

    public String sanitizeFileName(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalized = fileName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("非法文件名: " + fileName);
        }
        return name;
    }

    private Path userRoot(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return basePath.resolve("users").resolve(String.valueOf(userId));
    }
}
