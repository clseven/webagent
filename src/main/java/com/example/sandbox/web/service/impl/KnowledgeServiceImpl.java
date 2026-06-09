package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.exception.DuplicateFileException;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.entity.KnowledgeBaseEntity;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.response.FilePreviewContent;
import com.example.sandbox.web.repository.KnowledgeBaseRepository;
import com.example.sandbox.web.repository.KnowledgeChunkRepository;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import com.example.sandbox.web.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 知识库服务实现
 *
 * @author example
 * @date 2026/05/31
 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Autowired
    private DocumentParserService documentParserService;

    @Autowired
    private TextSplitterService textSplitterService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private SandboxClientFactory sandboxClientFactory;

    @Autowired
    private UserWorkspaceStorageService workspaceStorage;

    @Autowired
    private KnowledgeFileMigrationService migrationService;

    @Autowired
    private OfficePreviewService officePreviewService;

    // ========== 知识库 CRUD ==========

    @Override
    @Transactional
    public KnowledgeBaseEntity createKnowledgeBase(Long userId, String name, String description) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setUserId(userId);
        kb.setName(name);
        kb.setDescription(description);
        kb = knowledgeBaseRepository.save(kb);
        log.info("创建知识库: userId={}, kbId={}, name={}", userId, kb.getId(), name);
        return kb;
    }

    @Override
    public List<KnowledgeBaseEntity> listKnowledgeBases(Long userId) {
        return knowledgeBaseRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public KnowledgeBaseEntity getKnowledgeBase(Long kbId) {
        return knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new RuntimeException("知识库不存在: " + kbId));
    }

    @Override
    public KnowledgeBaseEntity getKnowledgeBase(Long userId, Long kbId) {
        return requireOwnedKnowledgeBase(userId, kbId);
    }

    @Override
    @Transactional
    public KnowledgeBaseEntity updateKnowledgeBase(Long kbId, String name, String description) {
        KnowledgeBaseEntity kb = getKnowledgeBase(kbId);
        if (name != null) kb.setName(name);
        if (description != null) kb.setDescription(description);
        kb.setUpdatedAt(java.time.LocalDateTime.now());
        return knowledgeBaseRepository.save(kb);
    }

    @Override
    @Transactional
    public KnowledgeBaseEntity updateKnowledgeBase(Long userId, Long kbId,
                                                   String name, String description) {
        KnowledgeBaseEntity kb = requireOwnedKnowledgeBase(userId, kbId);
        if (name != null) kb.setName(name);
        if (description != null) kb.setDescription(description);
        kb.setUpdatedAt(java.time.LocalDateTime.now());
        return knowledgeBaseRepository.save(kb);
    }

    @Override
    @Transactional
    public void deleteKnowledgeBase(Long kbId) {
        KnowledgeBaseEntity kb = getKnowledgeBase(kbId);

        // 删除该知识库下所有文档（包括切片、向量、本地文件）
        List<KnowledgeDocumentEntity> documents = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        for (KnowledgeDocumentEntity doc : documents) {
            deleteDocument(doc.getId());
        }

        // 删除 Milvus 中该知识库的所有向量
        try {
            vectorStoreService.deleteByKbId(kbId);
        } catch (Exception e) {
            log.warn("删除知识库 Milvus 向量失败: {}", e.getMessage());
        }

        // 删除知识库记录
        knowledgeBaseRepository.delete(kb);
        log.info("删除知识库完成: kbId={}", kbId);
    }

    @Override
    @Transactional
    public void deleteKnowledgeBase(Long userId, Long kbId) {
        requireOwnedKnowledgeBase(userId, kbId);
        for (KnowledgeDocumentEntity document : listDocuments(userId, kbId)) {
            deleteDocument(userId, document.getId());
        }
        try {
            vectorStoreService.deleteByKbId(kbId);
        } catch (Exception e) {
            log.warn("删除知识库 Milvus 向量失败: {}", e.getMessage());
        }
        knowledgeBaseRepository.delete(requireOwnedKnowledgeBase(userId, kbId));
    }

    @Override
    public String getKnowledgeBaseDescription(Long kbId) {
        KnowledgeBaseEntity kb = getKnowledgeBase(kbId);
        return kb.getDescription() != null ? kb.getDescription() : kb.getName();
    }

    // ========== 文档操作 ==========

    @Override
    public KnowledgeDocumentEntity upload(Long userId, Long kbId, MultipartFile file,
                                          String splitMode, Integer chunkSize, Integer overlap) {
        requireOwnedKnowledgeBase(userId, kbId);

        // 0. 重复检查（同一知识库 + 同一用户 + 忽略大小写）
        String originalFileName = workspaceStorage.sanitizeFileName(file.getOriginalFilename());
        Optional<KnowledgeDocumentEntity> existing = documentRepository
                .findByKbIdAndUserIdAndFileNameIgnoreCase(kbId, userId, originalFileName);
        if (existing.isPresent()) {
            log.info("检测到重复文件: userId={}, kbId={}, fileName={}, existingDocId={}",
                    userId, kbId, originalFileName, existing.get().getId());
            throw new DuplicateFileException(originalFileName, existing.get().getId());
        }

        // 1. 保存文档元数据
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setUserId(userId);
        document.setKbId(kbId);
        document.setFileName(originalFileName);
        document.setFileType(getFileExtension(originalFileName));
        document.setFileSize(file.getSize());
        document.setSplitMode(splitMode != null ? splitMode : "smart");
        document.setChunkSize(chunkSize);
        document.setOverlap(overlap);
        document = documentRepository.save(document);

        // 2. 保存文件到本地
        Path stored = workspaceStorage.write(
                workspaceStorage.knowledgeFile(userId, kbId, originalFileName),
                fileBytes(file));
        String storagePath = stored.toAbsolutePath().toString();
        document.setStoragePath(storagePath);
        documentRepository.save(document);

        // 3. 异步处理：解析 → 切片 → 向量化
        processDocumentAsync(document.getId(), storagePath, splitMode, chunkSize, overlap);

        return document;
    }

    @Async
    public void processDocumentAsync(Long docId, String filePath,
                                     String splitMode, Integer chunkSize, Integer overlap) {
        try {
            KnowledgeDocumentEntity document = documentRepository.findById(docId).orElse(null);
            if (document == null) {
                log.error("文档不存在: {}", docId);
                return;
            }

            document.setStatus("PROCESSING");
            documentRepository.save(document);

            // 1. 解析文档
            log.info("开始解析文档: {}", document.getFileName());
            String content = documentParserService.parse(filePath);

            // 2. 切片（带位置信息）
            log.info("开始切片文档: {}, 模式: {}", document.getFileName(), splitMode);
            List<TextSplitterService.ChunkWithPosition> chunkPositions;
            if ("custom".equals(splitMode) && chunkSize != null && chunkSize > 0) {
                int overlapVal = (overlap != null && overlap > 0) ? overlap : 0;
                chunkPositions = textSplitterService.splitCustomWithPosition(content, chunkSize, overlapVal);
            } else {
                chunkPositions = textSplitterService.splitSmartWithPosition(content);
            }

            if (chunkPositions.isEmpty()) {
                document.setStatus("FAILED");
                document.setErrorMsg("文档内容为空或无法切分");
                documentRepository.save(document);
                return;
            }

            List<String> chunks = new ArrayList<>();
            for (TextSplitterService.ChunkWithPosition cp : chunkPositions) {
                chunks.add(cp.getContent());
            }

            // 3. 向量化（从 API 获取真实 token 数）
            log.info("开始向量化文档: {}, 切片数: {}", document.getFileName(), chunks.size());
            EmbeddingService.EmbeddingResult embeddingResult = embeddingService.embedBatch(chunks);
            List<float[]> embeddings = embeddingResult.embeddings();
            int totalTokens = embeddingResult.promptTokens();
            int avgTokensPerChunk = totalTokens / chunks.size();

            // 4. 保存切片到 MySQL（带位置信息）
            List<Map<String, Object>> vectorItems = new ArrayList<>();
            for (int i = 0; i < chunkPositions.size(); i++) {
                TextSplitterService.ChunkWithPosition cp = chunkPositions.get(i);
                KnowledgeChunkEntity chunkEntity = new KnowledgeChunkEntity();
                chunkEntity.setDocumentId(docId);
                chunkEntity.setUserId(document.getUserId());
                chunkEntity.setKbId(document.getKbId());
                chunkEntity.setChunkIndex(i);
                chunkEntity.setContent(cp.getContent());
                chunkEntity.setTokenCount(avgTokensPerChunk);
                chunkEntity.setStartOffset(cp.getStartOffset());
                chunkEntity.setEndOffset(cp.getEndOffset());
                chunkRepository.save(chunkEntity);

                vectorItems.add(Map.of("chunkIndex", i, "embedding", (Object) embeddings.get(i)));
            }

            // 5. 保存向量到 Milvus
            vectorStoreService.insertBatch(document.getUserId(), document.getKbId(), docId, vectorItems);

            // 6. 同步文件到沙箱（用于预览，不阻塞主流程）
            boolean sandboxSynced = syncToSandbox(document, filePath);

            // 7. 更新状态
            document.setChunkCount(chunks.size());
            document.setTotalTokens(totalTokens);
            document.setSandboxSynced(sandboxSynced);
            document.setStatus("READY");
            documentRepository.save(document);

            log.info("文档处理完成: {}, 切片数: {}, 总 tokens: {}, 沙箱同步: {}",
                    document.getFileName(), chunks.size(), totalTokens, sandboxSynced);

        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            KnowledgeDocumentEntity document = documentRepository.findById(docId).orElse(null);
            if (document != null) {
                document.setStatus("FAILED");
                document.setErrorMsg(e.getMessage());
                documentRepository.save(document);
            }
        }
    }

    /**
     * 同步文件到用户沙箱（/home/gem/knowledge/）
     * <p>使用用户上传时的原始文件名（而不是 doc_{id}.{ext}），
     * 这样用户在沙箱里能直接看到自己上传的文件名。</p>
     *
     * @return 是否成功同步
     */
    private boolean syncToSandbox(KnowledgeDocumentEntity document, String localFilePath) {
        try {
            AioSandboxClient client = sandboxClientFactory.getAioClientByUserId(document.getUserId());
            if (client == null) {
                log.warn("用户 {} 无沙箱，跳过沙箱同步: docId={}", document.getUserId(), document.getId());
                return false;
            }

            String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                    document.getKbId(), document.getFileName());
            client.execCommand("mkdir -p " + quoteShell(parentPath(sandboxPath)));

            // 读取本地文件并写入沙箱
            byte[] fileBytes = Files.readAllBytes(Paths.get(localFilePath));
            boolean ok = client.writeFile(sandboxPath, fileBytes);
            if (ok) {
                log.info("文件已同步到沙箱: docId={}, path={}", document.getId(), sandboxPath);
            } else {
                log.warn("文件同步到沙箱失败: docId={}, path={}", document.getId(), sandboxPath);
            }
            return ok;
        } catch (Exception e) {
            log.error("同步文件到沙箱异常: docId={}", document.getId(), e);
            return false;
        }
    }

    @Override
    public List<KnowledgeDocumentEntity> listDocuments(Long kbId) {
        return documentRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    @Override
    public List<KnowledgeDocumentEntity> listDocuments(Long userId, Long kbId) {
        requireOwnedKnowledgeBase(userId, kbId);
        return documentRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    @Override
    public KnowledgeDocumentEntity getDocument(Long docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在: " + docId));
    }

    @Override
    public KnowledgeDocumentEntity getDocument(Long userId, Long docId) {
        return requireOwnedDocument(userId, docId);
    }

    @Override
    public List<KnowledgeChunkEntity> listChunks(Long docId) {
        return chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
    }

    @Override
    public List<KnowledgeChunkEntity> listChunks(Long userId, Long docId) {
        requireOwnedDocument(userId, docId);
        return chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
    }

    @Override
    public FilePreviewContent getPreviewContent(Long userId, Long docId) {
        KnowledgeDocumentEntity document = requireOwnedDocument(userId, docId);
        Path localFile = migrationService.ensureCanonicalFile(document);
        AioSandboxClient client = sandboxClientFactory.getAioClientByUserId(userId);
        if (client == null) {
            throw new RuntimeException("用户沙箱不可用，请稍后重试");
        }
        String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                document.getKbId(), document.getFileName());
        ensureSandboxFile(client, localFile, sandboxPath);
        if (officePreviewService.isConvertible(document.getFileName())) {
            return officePreviewService.previewKnowledge(client, document, sandboxPath);
        }
        byte[] content = client.downloadFile(sandboxPath);
        if (content == null || content.length == 0) {
            throw new RuntimeException("沙箱文件不存在或为空: " + sandboxPath);
        }
        return new FilePreviewContent(
                content,
                mediaType(document.getFileType()),
                document.getFileType(),
                document.getFileName());
    }

    @Override
    public byte[] getFileContent(Long userId, Long docId) {
        KnowledgeDocumentEntity document = getDocument(docId);
        // 权限校验
        if (!Objects.equals(document.getUserId(), userId)) {
            throw new RuntimeException("无权访问该文档");
        }
        AioSandboxClient client = sandboxClientFactory.getAioClientByUserId(userId);
        if (client == null) {
            throw new RuntimeException("用户沙箱不可用，请稍后重试");
        }
        migrationService.ensureCanonicalFile(document);
        String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                document.getKbId(), document.getFileName());
        byte[] content = client.downloadFile(sandboxPath);
        if (content == null || content.length == 0) {
            throw new RuntimeException("沙箱文件不存在或为空，请确认文件已成功同步: " + sandboxPath);
        }
        return content;
    }

    @Override
    @Transactional
    public KnowledgeDocumentEntity replaceDocument(Long userId, Long docId, MultipartFile file,
                                                   String splitMode, Integer chunkSize, Integer overlap) {
        KnowledgeDocumentEntity document = requireOwnedDocument(userId, docId);

        log.info("替换文档: userId={}, docId={}, newFileName={}", userId, docId, file.getOriginalFilename());

        // 1. 删除旧的 Milvus 向量
        try {
            vectorStoreService.deleteByDocId(docId);
        } catch (Exception e) {
            log.warn("替换文档时删除旧 Milvus 向量失败: {}", e.getMessage());
        }

        // 2. 删除旧的 MySQL 切片
        chunkRepository.deleteByDocumentId(docId);

        String oldFileName = document.getFileName();
        Path oldLocalPath = migrationService.ensureCanonicalFile(document);

        // 3. 删除沙箱内旧文件（使用旧文件名）
        deleteFromSandbox(document);

        // 4. 写入新的规范路径
        String newFileName = workspaceStorage.sanitizeFileName(file.getOriginalFilename());
        Path newLocalPath = workspaceStorage.write(
                workspaceStorage.knowledgeFile(userId, document.getKbId(), newFileName),
                fileBytes(file));
        if (!oldLocalPath.equals(newLocalPath)) {
            workspaceStorage.delete(oldLocalPath);
        }

        // 5. 更新文档元数据（保留 ID 和 createdAt，更新其他字段）
        document.setFileName(newFileName);
        document.setFileType(getFileExtension(newFileName));
        document.setFileSize(file.getSize());
        document.setStoragePath(newLocalPath.toAbsolutePath().toString());
        document.setSplitMode(splitMode != null ? splitMode : "smart");
        document.setChunkSize(chunkSize);
        document.setOverlap(overlap);
        document.setChunkCount(0);
        document.setTotalTokens(null);
        document.setStatus("PENDING");
        document.setErrorMsg(null);
        document.setSandboxSynced(false);
        documentRepository.save(document);

        // 6. 异步处理：解析 → 切片 → 向量化
        processDocumentAsync(document.getId(), document.getStoragePath(), splitMode, chunkSize, overlap);

        return document;
    }

    @Override
    @Transactional
    public void deleteDocument(Long docId) {
        KnowledgeDocumentEntity document = getDocument(docId);

        // 删除 Milvus 向量
        try {
            vectorStoreService.deleteByDocId(docId);
        } catch (Exception e) {
            log.warn("删除 Milvus 向量失败: {}", e.getMessage());
        }

        // 删除 MySQL 切片
        chunkRepository.deleteByDocumentId(docId);

        // 删除本地文件
        if (document.getStoragePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(document.getStoragePath()));
            } catch (IOException e) {
                log.warn("删除本地文件失败: {}", e.getMessage());
            }
        }

        // 删除沙箱内文件
        deleteFromSandbox(document);

        // 删除文档记录
        documentRepository.delete(document);
        log.info("文档删除完成: docId={}", docId);
    }

    @Override
    @Transactional
    public void deleteDocument(Long userId, Long docId) {
        requireOwnedDocument(userId, docId);
        deleteDocument(docId);
    }

    /**
     * 删除沙箱内的知识库文件
     * <p>失败不抛出，仅记录日志</p>
     */
    private void deleteFromSandbox(KnowledgeDocumentEntity document) {
        try {
            AioSandboxClient client = sandboxClientFactory.getAioClientByUserId(document.getUserId());
            if (client == null) {
                log.warn("用户 {} 无沙箱，跳过沙箱清理: docId={}", document.getUserId(), document.getId());
                return;
            }
            // 使用原始文件名（与 syncToSandbox 保持一致）
            String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                    document.getKbId(), document.getFileName());
            String result = client.execCommand("rm -f " + quoteShell(sandboxPath));
            log.info("沙箱文件清理: docId={}, path={}, result={}", document.getId(), sandboxPath, result);
        } catch (Exception e) {
            log.error("删除沙箱文件异常: docId={}", document.getId(), e);
        }
    }

    @Override
    @Transactional
    public void deleteAllByUser(Long userId) {
        // 删除 Milvus 向量
        try {
            vectorStoreService.deleteByUserId(userId);
        } catch (Exception e) {
            log.warn("删除 Milvus 向量失败: {}", e.getMessage());
        }

        // 删除 MySQL 切片
        chunkRepository.deleteByUserId(userId);

        // 删除本地文件目录
        try {
            Path userDir = workspaceStorage.knowledgeRoot(userId);
            if (Files.exists(userDir)) {
                Files.walk(userDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        } catch (IOException e) {
            log.warn("删除知识库目录失败: {}", e.getMessage());
        }

        // 删除文档记录
        List<KnowledgeDocumentEntity> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        documentRepository.deleteAll(documents);

        // 删除知识库记录
        List<KnowledgeBaseEntity> bases = knowledgeBaseRepository.findByUserIdOrderByCreatedAtDesc(userId);
        knowledgeBaseRepository.deleteAll(bases);

        log.info("用户知识库删除完成: userId={}", userId);
    }

    @Override
    public List<Map<String, Object>> search(Long userId, Long kbId, String query, int topK) {
        // 1. 问题向量化
        EmbeddingService.EmbeddingResult queryResult = embeddingService.embedBatch(List.of(query));
        float[] queryEmbedding = queryResult.embeddings().get(0);

        // 2. 向量检索（按知识库过滤）
        List<Map<String, Object>> vectorResults = vectorStoreService.search(userId, kbId, queryEmbedding, topK);

        // 3. 补充切片文本和文档信息
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> vr : vectorResults) {
            Long docId = ((Number) vr.get("docId")).longValue();
            int chunkIndex = ((Number) vr.get("chunkIndex")).intValue();
            float score = ((Number) vr.get("score")).floatValue();

            // 从 MySQL 获取切片文本
            KnowledgeChunkEntity chunk = chunkRepository.findByDocumentIdOrderByChunkIndex(docId)
                    .stream()
                    .filter(c -> c.getChunkIndex() == chunkIndex)
                    .findFirst()
                    .orElse(null);

            KnowledgeDocumentEntity document = documentRepository.findById(docId).orElse(null);

            Map<String, Object> result = new HashMap<>();
            result.put("docId", docId);
            result.put("docName", document != null ? document.getFileName() : "未知文档");
            result.put("chunkIndex", chunkIndex);
            result.put("content", chunk != null ? chunk.getContent() : "");
            result.put("score", score);
            results.add(result);
        }

        return results;
    }

    private byte[] fileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    private String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index > 0 ? path.substring(0, index) : path;
    }

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "txt";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private KnowledgeBaseEntity requireOwnedKnowledgeBase(Long userId, Long kbId) {
        KnowledgeBaseEntity knowledgeBase = getKnowledgeBase(kbId);
        if (!Objects.equals(knowledgeBase.getUserId(), userId)) {
            throw new UnauthorizedException("无权访问该知识库");
        }
        return knowledgeBase;
    }

    private KnowledgeDocumentEntity requireOwnedDocument(Long userId, Long docId) {
        KnowledgeDocumentEntity document = getDocument(docId);
        if (!Objects.equals(document.getUserId(), userId)) {
            throw new UnauthorizedException("无权访问该文档");
        }
        return document;
    }

    private void ensureSandboxFile(AioSandboxClient client, Path localFile, String sandboxPath) {
        if (client.fileExists(sandboxPath)) {
            return;
        }
        client.execCommand("mkdir -p " + quoteShell(parentPath(sandboxPath)));
        if (!client.writeFile(sandboxPath, workspaceStorage.read(localFile))) {
            throw new RuntimeException("恢复沙箱文件失败: " + sandboxPath);
        }
    }

    private String mediaType(String extension) {
        if (extension == null) {
            return "application/octet-stream";
        }
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            case "txt" -> "text/plain;charset=UTF-8";
            case "md", "markdown" -> "text/markdown;charset=UTF-8";
            case "csv" -> "text/csv;charset=UTF-8";
            case "json" -> "application/json;charset=UTF-8";
            default -> "application/octet-stream";
        };
    }
}
