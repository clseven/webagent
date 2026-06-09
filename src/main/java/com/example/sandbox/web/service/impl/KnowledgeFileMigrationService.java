package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeFileMigrationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileMigrationService.class);

    private final KnowledgeDocumentRepository repository;
    private final UserWorkspaceStorageService storage;

    public KnowledgeFileMigrationService(KnowledgeDocumentRepository repository,
                                         UserWorkspaceStorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Transactional
    public Path ensureCanonicalFile(KnowledgeDocumentEntity document) {
        Path canonical = storage.knowledgeFile(
                document.getUserId(),
                document.getKbId(),
                document.getFileName());
        if (Files.isRegularFile(canonical)) {
            updateStoragePath(document, canonical);
            return canonical;
        }
        if (document.getStoragePath() == null || document.getStoragePath().isBlank()) {
            throw new RuntimeException("知识库原文件路径为空: " + document.getId());
        }

        Path source = Paths.get(document.getStoragePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new RuntimeException("知识库原文件不存在: " + source);
        }

        byte[] bytes = storage.read(source);
        storage.write(canonical, bytes);
        try {
            if (Files.size(canonical) != bytes.length) {
                throw new RuntimeException("迁移文件大小校验失败: " + canonical);
            }
        } catch (IOException e) {
            throw new RuntimeException("迁移文件校验失败: " + canonical, e);
        }

        updateStoragePath(document, canonical);
        if (!source.equals(canonical)) {
            try {
                Files.deleteIfExists(source);
            } catch (IOException e) {
                log.warn("旧知识库文件保留，稍后可清理: {}", source, e);
            }
        }
        return canonical;
    }

    @Transactional
    public List<Path> migrateUser(Long userId) {
        List<Path> migrated = new ArrayList<>();
        for (KnowledgeDocumentEntity document : repository.findByUserIdOrderByIdAsc(userId)) {
            try {
                migrated.add(ensureCanonicalFile(document));
            } catch (RuntimeException e) {
                log.warn("知识库文件迁移失败: userId={}, docId={}",
                        userId, document.getId(), e);
            }
        }
        return List.copyOf(migrated);
    }

    private void updateStoragePath(KnowledgeDocumentEntity document, Path canonical) {
        String path = canonical.toAbsolutePath().toString();
        if (!path.equals(document.getStoragePath())) {
            document.setStoragePath(path);
            repository.save(document);
        }
    }
}
