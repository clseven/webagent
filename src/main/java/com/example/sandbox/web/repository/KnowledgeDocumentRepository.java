package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文档 Repository
 *
 * @author example
 * @date 2026/05/31
 */
@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

    /**
     * 按用户 ID 查询文档列表
     */
    List<KnowledgeDocumentEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<KnowledgeDocumentEntity> findByUserIdOrderByIdAsc(Long userId);

    /**
     * 按知识库 ID 查询文档列表
     */
    List<KnowledgeDocumentEntity> findByKbIdOrderByCreatedAtDesc(Long kbId);

    /**
     * 按用户 ID 和状态查询
     */
    List<KnowledgeDocumentEntity> findByUserIdAndStatus(Long userId, String status);

    /**
     * 按状态查询（用于启动时自愈卡在 PROCESSING 的僵尸文档）
     */
    List<KnowledgeDocumentEntity> findByStatus(String status);

    /**
     * 统计用户文档数
     */
    long countByUserId(Long userId);

    /**
     * 统计知识库文档数
     */
    long countByKbId(Long kbId);

    /**
     * 检查同一知识库下是否存在同名文件（忽略大小写）
     *
     * <p>用于上传时的重复检测：test.pdf 和 Test.pdf 视为同名。</p>
     */
    Optional<KnowledgeDocumentEntity> findByKbIdAndUserIdAndFileNameIgnoreCase(
            Long kbId, Long userId, String fileName);
}
