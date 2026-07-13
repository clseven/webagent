package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.exception.DuplicateFileException;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.KnowledgeBaseEntity;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.response.FilePreviewContent;
import com.example.sandbox.web.repository.KnowledgeBaseRepository;
import com.example.sandbox.web.repository.KnowledgeChunkRepository;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import com.example.sandbox.web.service.*;
import com.example.sandbox.web.service.enhance.QueryRewriteService;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RawChunk;
import com.example.sandbox.web.service.enhance.RerankResult;
import com.example.sandbox.web.service.enhance.RerankService;
import com.example.sandbox.web.service.enhance.RerankResultFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

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
    private QueryRewriteService queryRewriteService;

    @Autowired
    private RerankService rerankService;

    /** RAG 配置，用于读取页面和工具检索共享的默认最低相关度。 */
    @Autowired
    private RagConfigProperties ragConfigProperties;

    @Autowired
    private OfficePreviewService officePreviewService;

    @Autowired
    private KnowledgeDocumentProcessor documentProcessor;

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

        // 3. 异步处理：解析 → 切片 → 向量化（跨 Bean 调用，@Async 通过代理生效）
        documentProcessor.processDocumentAsync(document.getId(), storagePath, splitMode, chunkSize, overlap);

        return document;
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
        AioClient client = sandboxClientFactory.getAioClientByUserId(userId);
        if (client == null) {
            throw new RuntimeException("用户沙箱不可用，请稍后重试");
        }
        String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                document.getKbId(), document.getFileName());
        ensureSandboxFile(client, localFile, sandboxPath);
        if (officePreviewService.isConvertible(document.getFileName())) {
            return officePreviewService.previewKnowledge(client, document, sandboxPath);
        }
        byte[] content = client.files().download(sandboxPath);
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
        AioClient client = sandboxClientFactory.getAioClientByUserId(userId);
        if (client == null) {
            throw new RuntimeException("用户沙箱不可用，请稍后重试");
        }
        migrationService.ensureCanonicalFile(document);
        String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                document.getKbId(), document.getFileName());
        byte[] content = client.files().download(sandboxPath);
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

        // 6. 异步处理：解析 → 切片 → 向量化（跨 Bean 调用，@Async 通过代理生效）
        documentProcessor.processDocumentAsync(document.getId(), document.getStoragePath(), splitMode, chunkSize, overlap);

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
            AioClient client = sandboxClientFactory.getAioClientByUserId(document.getUserId());
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

    /**
     * 删除用户在沙箱内的整个知识库根目录 {@code /home/gem/knowledge}。
     *
     * <p>用于账号注销：沙箱按用户独占，该目录整体属于当前用户，一次 {@code rm -rf}
     * 即可清空所有知识库文件，避免逐文档删除的孤儿残留。失败仅记日志，不抛出。</p>
     */
    private void deleteKnowledgeRootFromSandbox(Long userId) {
        try {
            AioClient client = sandboxClientFactory.getAioClientByUserId(userId);
            if (client == null) {
                log.warn("用户 {} 无沙箱，跳过沙箱知识库目录清理", userId);
                return;
            }
            // 与 UserWorkspaceStorageService.KNOWLEDGE_SANDBOX_ROOT 保持一致
            String knowledgeRoot = "/home/gem/knowledge";
            String result = client.execCommand("rm -rf " + quoteShell(knowledgeRoot));
            log.info("沙箱知识库目录清理: userId={}, path={}, result={}", userId, knowledgeRoot, result);
        } catch (Exception e) {
            log.error("删除沙箱知识库目录异常: userId={}", userId, e);
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

        // 删除沙箱内知识库目录（整个 /home/gem/knowledge，沙箱按用户独占，一次清空最彻底）
        deleteKnowledgeRootFromSandbox(userId);

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
        return search(userId, kbId, query, topK, null);
    }

    /**
     * 执行知识库检索，并在重排后按本次请求或系统默认阈值过滤。
     *
     * @param userId 用户 ID
     * @param kbId 知识库 ID
     * @param query 查询文本
     * @param topK 最大返回数量
     * @param minScore 本次最低相关度；为 null 时使用 RAG 默认配置
     * @return 先过滤最低相关度、再限制 topK 的结果
     */
    @Override
    public List<Map<String, Object>> search(Long userId, Long kbId, String query, int topK, Float minScore) {
        List<Long> kbIds = kbId == null ? List.of() : List.of(kbId);
        return search(userId, kbIds, query, List.of(), topK, minScore);
    }

    /**
     * 在指定知识库集合中执行统一的查询改写、向量召回、去重、重排和阈值过滤。
     *
     * <p>该方法是 REST 搜索、Agent 自动预检索和 knowledge_search 工具共享的唯一检索流水线。
     * 向量召回阶段不设置业务相关度阈值；最低相关度仅在重排服务成功时生效。</p>
     *
     * @param userId 当前用户 ID
     * @param kbIds 允许检索的知识库 ID 集合
     * @param query 原始查询文本
     * @param history 查询改写使用的对话历史
     * @param topK 最大返回数量
     * @param minScore 最低重排相关度；为 null 时使用系统默认值
     * @return 跨知识库统一排序后的结果
     */
    @Override
    public List<Map<String, Object>> search(Long userId, List<Long> kbIds, String query,
                                            List<ChatMessage> history, int topK, Float minScore) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<Long> scopedKbIds = kbIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (scopedKbIds.isEmpty()) {
            return List.of();
        }

        // 1. Query Rewrite：自动预检索可传历史，页面和工具传空历史。
        List<ChatMessage> safeHistory = history == null ? List.of() : history;
        List<String> queries = queryRewriteService.rewrite(query, safeHistory);
        log.info("Query Rewrite: {} -> {}", query, queries);
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }

        // 2. 所有改写查询一次性向量化，再对限定知识库做跨库召回。
        int retrieveTopK = Math.max(Math.max(topK * 3, 20),
                ragConfigProperties.getEnhancement().getRetrieve().getTopN());
        List<Map<String, Object>> allVectorResults = new ArrayList<>();
        EmbeddingService.EmbeddingResult queryResult = embeddingService.embedBatch(queries);
        List<float[]> embeddings = queryResult.embeddings();
        for (float[] queryEmbedding : embeddings) {
            for (Long scopedKbId : scopedKbIds) {
                allVectorResults.addAll(
                        vectorStoreService.search(userId, scopedKbId, queryEmbedding, retrieveTopK));
            }
        }

        // 3. 跨查询、跨知识库去重，相同片段保留最高向量分数，不做 0.5 等预过滤。
        Map<String, Map<String, Object>> dedupMap = new LinkedHashMap<>();
        for (Map<String, Object> r : allVectorResults) {
            String key = r.get("docId") + ":" + r.get("chunkIndex");
            Map<String, Object> existing = dedupMap.get(key);
            if (existing == null
                    || ((Number) r.get("score")).floatValue()
                    > ((Number) existing.get("score")).floatValue()) {
                dedupMap.put(key, r);
            }
        }
        List<Map<String, Object>> vectorResults = new ArrayList<>(dedupMap.values());
        if (vectorResults.isEmpty()) {
            return List.of();
        }

        // 4. 批量补充切片文本（按 docId IN 一次查回，避免 N+1 查询）。
        List<Long> docIds = vectorResults.stream()
                .map(r -> ((Number) r.get("docId")).longValue())
                .distinct()
                .toList();
        Map<Long, String> docNameMap = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(
                        KnowledgeDocumentEntity::getId,
                        document -> Optional.ofNullable(document.getFileName()).orElse("未知文档"),
                        (first, ignored) -> first));
        Map<Long, List<KnowledgeChunkEntity>> chunksByDoc = new HashMap<>();
        if (!docIds.isEmpty()) {
            for (KnowledgeChunkEntity c : chunkRepository.findByDocumentIdIn(docIds)) {
                chunksByDoc.computeIfAbsent(c.getDocumentId(), k -> new ArrayList<>()).add(c);
            }
        }
        for (Map<String, Object> vr : vectorResults) {
            Long docId = ((Number) vr.get("docId")).longValue();
            int chunkIndex = ((Number) vr.get("chunkIndex")).intValue();
            String content = chunksByDoc.getOrDefault(docId, List.of()).stream()
                    .filter(c -> c.getChunkIndex() == chunkIndex)
                    .findFirst()
                    .map(KnowledgeChunkEntity::getContent)
                    .orElse("");
            vr.put("content", content);
        }

        // 5. 全部知识库候选只执行一次全局 Rerank。
        List<RawChunk> candidates = vectorResults.stream()
                .map(r -> new RawChunk(
                        ((Number) r.get("docId")).longValue(),
                        ((Number) r.get("chunkIndex")).intValue(),
                        (String) r.get("content"),
                        ((Number) r.get("score")).floatValue()
                ))
                .toList();

        float effectiveMinScore = minScore != null
                ? minScore
                : ragConfigProperties.getEnhancement().getRerank().getMinScore();
        RerankResult rerankResult = rerankService.rerank(query, candidates);
        List<RankedChunk> ranked = RerankResultFilter.filter(rerankResult, effectiveMinScore, topK);

        // 6. 构建最终结果。
        List<Map<String, Object>> results = new ArrayList<>();
        for (RankedChunk rc : ranked) {
            Map<String, Object> m = new HashMap<>();
            m.put("docId", rc.docId());
            m.put("docName", docNameMap.getOrDefault(rc.docId(), "未知文档"));
            m.put("chunkIndex", rc.chunkIndex());
            m.put("content", rc.content());
            m.put("score", rc.score());
            results.add(m);
        }

        log.info("检索完成: 知识库 {} 个, Query {} 个, 向量召回 {} 个, 重排成功={}, 最终 {} 个",
                scopedKbIds.size(), queries.size(), vectorResults.size(), rerankResult.reranked(), results.size());
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

    private void ensureSandboxFile(AioClient client, Path localFile, String sandboxPath) {
        if (client.shell().fileExists(sandboxPath)) {
            return;
        }
        client.execCommand("mkdir -p " + quoteShell(parentPath(sandboxPath)));
        if (!client.files().writeBytes(sandboxPath, workspaceStorage.read(localFile))) {
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
