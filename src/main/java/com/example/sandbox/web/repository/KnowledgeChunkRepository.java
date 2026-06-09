package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.KnowledgeChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 知识切片 Repository
 *
 * @author example
 * @date 2026/05/31
 */
@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {

    /**
     * 按文档 ID 查询切片
     */
    List<KnowledgeChunkEntity> findByDocumentIdOrderByChunkIndex(Long documentId);

    /**
     * 按用户 ID 查询切片
     */
    List<KnowledgeChunkEntity> findByUserId(Long userId);

    /**
     * 按文档 ID 列表查询切片
     *
     * <p>用于批量获取检索命中的切片内容</p>
     */
    @Query("SELECT k FROM KnowledgeChunkEntity k WHERE k.documentId IN :docIds")
    List<KnowledgeChunkEntity> findByDocumentIdIn(@Param("docIds") List<Long> docIds);

    /**
     * 按文档 ID 删除切片
     */
    @Modifying
    @Query("DELETE FROM KnowledgeChunkEntity k WHERE k.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按用户 ID 删除切片
     */
    @Modifying
    @Query("DELETE FROM KnowledgeChunkEntity k WHERE k.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /**
     * 统计文档切片数
     */
    long countByDocumentId(Long documentId);
}
