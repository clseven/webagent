package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.KnowledgeBaseEntity;
import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.response.FilePreviewContent;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库服务接口
 *
 * @author example
 * @date 2026/05/31
 */
public interface KnowledgeService {

    // ========== 知识库 CRUD ==========

    /**
     * 创建知识库
     */
    KnowledgeBaseEntity createKnowledgeBase(Long userId, String name, String description);

    /**
     * 列出用户的知识库
     */
    List<KnowledgeBaseEntity> listKnowledgeBases(Long userId);

    /**
     * 获取知识库详情
     */
    KnowledgeBaseEntity getKnowledgeBase(Long kbId);

    KnowledgeBaseEntity getKnowledgeBase(Long userId, Long kbId);

    /**
     * 更新知识库
     */
    KnowledgeBaseEntity updateKnowledgeBase(Long kbId, String name, String description);

    KnowledgeBaseEntity updateKnowledgeBase(Long userId, Long kbId, String name, String description);

    /**
     * 删除知识库（级联删除所有文档、切片、向量）
     */
    void deleteKnowledgeBase(Long kbId);

    void deleteKnowledgeBase(Long userId, Long kbId);

    /**
     * 获取知识库描述（用于注入工具描述）
     */
    String getKnowledgeBaseDescription(Long kbId);

    // ========== 文档操作 ==========

    /**
     * 上传文档到指定知识库
     *
     * <p>上传前会检查是否已有同名文件（同一知识库、同一用户，忽略大小写），
     * 如果存在则抛出 {@link com.example.sandbox.web.exception.DuplicateFileException}。</p>
     *
     * @param userId    用户 ID
     * @param kbId      知识库 ID
     * @param file      上传文件
     * @param splitMode 切片模式: smart(智能) / custom(自定义)
     * @param chunkSize 自定义模式下的切片字符数（smart 模式忽略）
     * @param overlap   自定义模式下的重叠字符数（smart 模式忽略）
     * @return 文档实体
     * @throws com.example.sandbox.web.exception.DuplicateFileException 文件重复时抛出
     */
    KnowledgeDocumentEntity upload(Long userId, Long kbId, MultipartFile file,
                                   String splitMode, Integer chunkSize, Integer overlap);

    /**
     * 替换已有文档（用新文件覆盖旧文档的内容和切片）
     *
     * <p>删除旧文档的所有切片和向量，保留文档 ID 不变（方便前端更新状态），
     * 然后用新文件重新走解析 → 切片 → 向量化流程。</p>
     *
     * @param userId    用户 ID
     * @param docId     要替换的文档 ID
     * @param file      新文件
     * @param splitMode 切片模式
     * @param chunkSize 自定义切片大小
     * @param overlap   自定义重叠
     * @return 替换后的文档实体
     */
    KnowledgeDocumentEntity replaceDocument(Long userId, Long docId, MultipartFile file,
                                            String splitMode, Integer chunkSize, Integer overlap);

    /**
     * 列出知识库下的文档
     *
     * @param kbId 知识库 ID
     * @return 文档列表
     */
    List<KnowledgeDocumentEntity> listDocuments(Long kbId);

    List<KnowledgeDocumentEntity> listDocuments(Long userId, Long kbId);

    /**
     * 获取文档详情
     *
     * @param docId 文档 ID
     * @return 文档实体
     */
    KnowledgeDocumentEntity getDocument(Long docId);

    KnowledgeDocumentEntity getDocument(Long userId, Long docId);

    /**
     * 列出文档的所有切片（带原文位置信息）
     * <p>用于文件预览时切片列表展示与原文位置联动</p>
     *
     * @param docId 文档 ID
     * @return 切片列表
     */
    List<KnowledgeChunkEntity> listChunks(Long docId);

    List<KnowledgeChunkEntity> listChunks(Long userId, Long docId);

    FilePreviewContent getPreviewContent(Long userId, Long docId);

    /**
     * 读取文档原文件内容（从用户沙箱读取）
     * <p>用于文件预览，调用方需提供 userId 以做权限校验</p>
     *
     * @param userId 当前用户 ID
     * @param docId  文档 ID
     * @return 文件二进制内容
     */
    byte[] getFileContent(Long userId, Long docId);

    /**
     * 删除文档
     *
     * @param docId 文档 ID
     */
    void deleteDocument(Long docId);

    void deleteDocument(Long userId, Long docId);

    /**
     * 删除用户的所有知识库数据
     *
     * @param userId 用户 ID
     */
    void deleteAllByUser(Long userId);

    /**
     * 向量检索（在指定知识库中检索）
     *
     * @param userId 用户 ID
     * @param kbId   知识库 ID（可选，为 null 时检索该用户所有知识库）
     * @param query  查询文本
     * @param topK   返回数量
     * @return 检索结果列表
     */
    List<Map<String, Object>> search(Long userId, Long kbId, String query, int topK);
}
