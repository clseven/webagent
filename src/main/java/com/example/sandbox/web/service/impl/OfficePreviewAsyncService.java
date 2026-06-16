package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Office 文件异步预转换服务
 *
 * <p>在文件上传后异步将 Office 文件转换为 PDF 并缓存，
 * 这样用户首次预览时可以直接使用缓存，无需等待转换。</p>
 *
 * @author example
 * @date 2026/06/15
 */
@Service
public class OfficePreviewAsyncService {

    private static final Logger log = LoggerFactory.getLogger(OfficePreviewAsyncService.class);

    @Autowired
    private OfficePreviewService officePreviewService;

    @Autowired
    private SandboxClientFactory sandboxClientFactory;

    /**
     * 异步预转换 Office 文件（知识库文件）
     *
     * @param userId 用户 ID
     * @param kbId 知识库 ID
     * @param documentId 文档 ID
     * @param sandboxPath 沙箱文件路径
     */
    @Async
    public void convertKnowledgeFileAsync(Long userId, Long kbId, Long documentId, String sandboxPath) {
        if (!officePreviewService.isConvertible(sandboxPath)) {
            return;
        }

        try {
            AioClient client = sandboxClientFactory.getAioClientByUserId(userId);
            if (client == null) {
                log.warn("用户 {} 无沙箱，跳过预转换: docId={}", userId, documentId);
                return;
            }

            // 构造知识库文档对象用于缓存路径计算
            var document = new com.example.sandbox.web.model.entity.KnowledgeDocumentEntity();
            document.setId(documentId);
            document.setKbId(kbId);
            document.setUserId(userId);

            officePreviewService.previewKnowledge(client, document, sandboxPath);
            log.info("知识库 Office 文件预转换完成: docId={}, path={}", documentId, sandboxPath);
        } catch (Exception e) {
            log.warn("知识库 Office 文件预转换失败（不影响主流程）: docId={}, path={}", documentId, sandboxPath, e);
        }
    }

    /**
     * 异步预转换 Office 文件（工作空间文件）
     *
     * @param userId 用户 ID
     * @param sandboxPath 沙箱文件路径
     */
    @Async
    public void convertWorkspaceFileAsync(Long userId, String sandboxPath) {
        if (!officePreviewService.isConvertible(sandboxPath)) {
            return;
        }

        try {
            AioClient client = sandboxClientFactory.getAioClientByUserId(userId);
            if (client == null) {
                log.warn("用户 {} 无沙箱，跳过预转换: path={}", userId, sandboxPath);
                return;
            }

            officePreviewService.previewWorkspace(client, sandboxPath);
            log.info("工作空间 Office 文件预转换完成: path={}", sandboxPath);
        } catch (Exception e) {
            log.warn("工作空间 Office 文件预转换失败（不影响主流程）: path={}", sandboxPath, e);
        }
    }
}
