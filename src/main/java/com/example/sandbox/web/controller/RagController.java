package com.example.sandbox.web.controller;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.KnowledgeBaseEntity;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.request.SearchRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.model.response.DocumentResponse;
import com.example.sandbox.web.model.response.FilePreviewContent;
import com.example.sandbox.web.model.response.KnowledgeBaseResponse;
import com.example.sandbox.web.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * RAG 知识库控制器
 *
 * @author example
 * @date 2026/05/31
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    @Autowired
    private KnowledgeService knowledgeService;

    // ========== 知识库 CRUD ==========

    /**
     * 创建知识库
     */
    @PostMapping("/bases")
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getCurrentUserId();
        String name = body.get("name");
        String description = body.get("description");
        log.info("创建知识库: userId={}, name={}", userId, name);
        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(userId, name, description);
        return ApiResponse.success(KnowledgeBaseResponse.from(kb));
    }

    /**
     * 列出用户的知识库
     */
    @GetMapping("/bases")
    public ApiResponse<List<KnowledgeBaseResponse>> listKnowledgeBases() {
        Long userId = UserContext.getCurrentUserId();
        List<KnowledgeBaseResponse> bases = knowledgeService.listKnowledgeBases(userId)
                .stream()
                .map(KnowledgeBaseResponse::from)
                .collect(Collectors.toList());
        return ApiResponse.success(bases);
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/bases/{kbId}")
    public ApiResponse<KnowledgeBaseResponse> getKnowledgeBase(@PathVariable Long kbId) {
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(
                UserContext.getCurrentUserId(), kbId);
        return ApiResponse.success(KnowledgeBaseResponse.from(kb));
    }

    /**
     * 更新知识库
     */
    @PutMapping("/bases/{kbId}")
    public ApiResponse<KnowledgeBaseResponse> updateKnowledgeBase(
            @PathVariable Long kbId, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String description = body.get("description");
        log.info("更新知识库: kbId={}", kbId);
        KnowledgeBaseEntity kb = knowledgeService.updateKnowledgeBase(
                UserContext.getCurrentUserId(), kbId, name, description);
        return ApiResponse.success(KnowledgeBaseResponse.from(kb));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/bases/{kbId}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable Long kbId) {
        log.info("删除知识库: kbId={}", kbId);
        knowledgeService.deleteKnowledgeBase(UserContext.getCurrentUserId(), kbId);
        return ApiResponse.success();
    }

    // ========== 文档操作 ==========

    /**
     * 上传文档到指定知识库
     */
    @PostMapping("/bases/{kbId}/documents/upload")
    public ApiResponse<DocumentResponse> upload(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitMode", defaultValue = "smart") String splitMode,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "overlap", required = false) Integer overlap) {
        Long userId = UserContext.getCurrentUserId();
        log.info("上传知识库文档: userId={}, kbId={}, fileName={}, splitMode={}", userId, kbId, file.getOriginalFilename(), splitMode);
        KnowledgeDocumentEntity document = knowledgeService.upload(userId, kbId, file, splitMode, chunkSize, overlap);
        return ApiResponse.success(DocumentResponse.from(document));
    }

    /**
     * 列出知识库下的文档
     */
    @GetMapping("/bases/{kbId}/documents")
    public ApiResponse<List<DocumentResponse>> listDocuments(@PathVariable Long kbId) {
        List<DocumentResponse> documents = knowledgeService.listDocuments(
                        UserContext.getCurrentUserId(), kbId)
                .stream()
                .map(DocumentResponse::from)
                .collect(Collectors.toList());
        return ApiResponse.success(documents);
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/document/{docId}")
    public ApiResponse<DocumentResponse> getDocument(@PathVariable Long docId) {
        KnowledgeDocumentEntity document = knowledgeService.getDocument(
                UserContext.getCurrentUserId(), docId);
        return ApiResponse.success(DocumentResponse.from(document));
    }

    /**
     * 获取文档的所有切片（含原文位置信息）
     * <p>用于文件预览时切片列表展示与原文位置联动</p>
     */
    @GetMapping("/document/{docId}/chunks")
    public ApiResponse<List<Map<String, Object>>> listChunks(@PathVariable Long docId) {
        List<KnowledgeChunkEntity> chunks = knowledgeService.listChunks(
                UserContext.getCurrentUserId(), docId);
        List<Map<String, Object>> result = chunks.stream().map(c -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", c.getId());
            m.put("chunkIndex", c.getChunkIndex());
            m.put("content", c.getContent());
            m.put("tokenCount", c.getTokenCount());
            m.put("startOffset", c.getStartOffset());
            m.put("endOffset", c.getEndOffset());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 获取文档原文件内容（从沙箱读取，用于预览）
     */
    @GetMapping("/document/{docId}/file")
    public ResponseEntity<byte[]> getFile(@PathVariable Long docId) {
        Long userId = UserContext.getCurrentUserId();
        log.info("读取知识库文档原文件: userId={}, docId={}", userId, docId);
        FilePreviewContent preview = knowledgeService.getPreviewContent(userId, docId);
        String disposition = ContentDisposition.inline()
                .filename(preview.originalFileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(preview.mediaType()))
                .body(preview.content());
    }

    /**
     * 替换已有文档（用新文件覆盖旧文档的内容和切片）
     */
    @PutMapping("/document/{docId}/replace")
    public ApiResponse<DocumentResponse> replaceDocument(
            @PathVariable Long docId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitMode", defaultValue = "smart") String splitMode,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "overlap", required = false) Integer overlap) {
        Long userId = UserContext.getCurrentUserId();
        log.info("替换知识库文档: userId={}, docId={}, newFileName={}", userId, docId, file.getOriginalFilename());
        KnowledgeDocumentEntity document = knowledgeService.replaceDocument(userId, docId, file, splitMode, chunkSize, overlap);
        return ApiResponse.success(DocumentResponse.from(document));
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/document/{docId}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long docId) {
        log.info("删除知识库文档: docId={}", docId);
        knowledgeService.deleteDocument(UserContext.getCurrentUserId(), docId);
        return ApiResponse.success();
    }

    /**
     * 向量检索（在指定知识库中检索，自动 Query Rewrite + Rerank）
     */
    @PostMapping("/bases/{kbId}/search")
    public ApiResponse<List<Map<String, Object>>> search(
            @PathVariable Long kbId, @RequestBody SearchRequest request) {
        Long userId = UserContext.getCurrentUserId();
        log.info("知识库检索: userId={}, kbId={}, query={}", userId, kbId, request.getQuery());
        List<Map<String, Object>> results = knowledgeService.search(
                userId, kbId, request.getQuery(), request.getTopK());
        return ApiResponse.success(results);
    }
}
