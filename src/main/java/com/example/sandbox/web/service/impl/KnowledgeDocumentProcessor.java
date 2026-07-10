package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.repository.KnowledgeChunkRepository;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import com.example.sandbox.web.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档异步处理器
 *
 * <p>从 {@link KnowledgeServiceImpl} 拆出的文档处理链路：解析 → 切片 → 向量化 → 沙箱同步。
 * 拆到独立 Bean 是为了让 {@link Async} 注解通过 Spring 代理生效——同类内部自调用
 * 不走代理，会导致 @Async 失效、整个处理在请求线程里同步执行，阻塞上传接口。</p>
 *
 * @author example
 * @date 2026/07/07
 */
@Service
public class KnowledgeDocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentProcessor.class);

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
    private OfficePreviewAsyncService officePreviewAsyncService;

    /**
     * 异步处理文档：解析 → 切片 → 向量化 → 沙箱同步。
     *
     * <p>通过 {@code knowledgeTaskExecutor} 有界线程池执行，避免阻塞上传请求线程。
     * 注意：本方法必须由其他 Bean 调用才能走代理、真正异步，不要在同类内自调用。</p>
     */
    @Async("knowledgeTaskExecutor")
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

            // 7. 异步预转换 Office 文件（不阻塞主流程）
            if (sandboxSynced) {
                String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                        document.getKbId(), document.getFileName());
                officePreviewAsyncService.convertKnowledgeFileAsync(
                        document.getUserId(), document.getKbId(), docId, sandboxPath);
            }

            // 8. 更新状态
            document.setChunkCount(chunks.size());
            document.setTotalTokens(totalTokens);
            document.setSandboxSynced(sandboxSynced);
            document.setStatus("READY");
            documentRepository.save(document);

            log.info("文档处理完成: {}, 切片数: {}, 总 tokens: {}, 沙箱同步: {}",
                    document.getFileName(), chunks.size(), totalTokens, sandboxSynced);

        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            cleanupPartialData(docId);
            KnowledgeDocumentEntity document = documentRepository.findById(docId).orElse(null);
            if (document != null) {
                document.setChunkCount(0);
                document.setTotalTokens(null);
                document.setStatus("FAILED");
                document.setErrorMsg(e.getMessage());
                documentRepository.save(document);
            }
        }
    }

    /**
     * 清理异步处理中途失败留下的脏数据：已写入的 Milvus 向量和 MySQL 切片。
     *
     * <p>切片可能只写了一部分（逐条 save），向量可能还没写或写了一部分，
     * 全部按 docId 删除，保证失败后不残留半份数据。清理本身失败仅记日志，
     * 不覆盖原始异常。</p>
     */
    private void cleanupPartialData(Long docId) {
        try {
            vectorStoreService.deleteByDocId(docId);
        } catch (Exception ex) {
            log.warn("失败清理：删除 Milvus 向量异常: docId={}, {}", docId, ex.getMessage());
        }
        try {
            List<KnowledgeChunkEntity> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
            if (!chunks.isEmpty()) {
                chunkRepository.deleteAll(chunks);
            }
        } catch (Exception ex) {
            log.warn("失败清理：删除 MySQL 切片异常: docId={}, {}", docId, ex.getMessage());
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
            AioClient client = sandboxClientFactory.getAioClientByUserId(document.getUserId());
            if (client == null) {
                log.warn("用户 {} 无沙箱，跳过沙箱同步: docId={}", document.getUserId(), document.getId());
                return false;
            }

            String sandboxPath = workspaceStorage.knowledgeSandboxPath(
                    document.getKbId(), document.getFileName());
            client.execCommand("mkdir -p " + quoteShell(parentPath(sandboxPath)));

            // 读取本地文件并写入沙箱
            byte[] fileBytes = Files.readAllBytes(Paths.get(localFilePath));
            boolean ok = client.files().writeBytes(sandboxPath, fileBytes);
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

    private String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index > 0 ? path.substring(0, index) : path;
    }

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
