package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.repository.KnowledgeChunkRepository;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import com.example.sandbox.web.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库文档处理自愈器。
 *
 * <p>{@link KnowledgeDocumentProcessor} 的异步处理不在事务里，进程中断（重启、崩溃）会让文档
 * 永久卡在 {@code PROCESSING}，且切片/向量可能只写了一半。本 Runner 在应用就绪后扫描所有
 * {@code PROCESSING} 文档，清理其半份数据并标记为 {@code FAILED}，让用户可以重新上传/替换。</p>
 *
 * @author example
 * @date 2026/07/10
 */
@Component
public class KnowledgeProcessingRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProcessingRecoveryRunner.class);

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Override
    public void run(ApplicationArguments args) {
        List<KnowledgeDocumentEntity> zombies = documentRepository.findByStatus("PROCESSING");
        if (zombies.isEmpty()) {
            return;
        }
        log.warn("检测到 {} 个卡在 PROCESSING 的僵尸文档，开始自愈", zombies.size());
        for (KnowledgeDocumentEntity document : zombies) {
            Long docId = document.getId();
            try {
                vectorStoreService.deleteByDocId(docId);
            } catch (Exception e) {
                log.warn("自愈：删除 Milvus 向量异常: docId={}, {}", docId, e.getMessage());
            }
            try {
                List<KnowledgeChunkEntity> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
                if (!chunks.isEmpty()) {
                    chunkRepository.deleteAll(chunks);
                }
            } catch (Exception e) {
                log.warn("自愈：删除 MySQL 切片异常: docId={}, {}", docId, e.getMessage());
            }
            document.setChunkCount(0);
            document.setTotalTokens(null);
            document.setStatus("FAILED");
            document.setErrorMsg("处理过程中服务中断，已自动标记为失败，请重新上传或替换");
            documentRepository.save(document);
            log.info("自愈完成: docId={}, fileName={}", docId, document.getFileName());
        }
    }
}
