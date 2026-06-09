package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.UserSandboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户沙箱 Repository
 *
 * @author example
 * @date 2026/05/31
 */
@Repository
public interface UserSandboxRepository extends JpaRepository<UserSandboxEntity, Long> {

    /**
     * 查询所有未删除的记录
     */
    List<UserSandboxEntity> findByDeletedFalse();

    /**
     * 根据 userId 查询未删除的记录
     */
    Optional<UserSandboxEntity> findByUserIdAndDeletedFalse(Long userId);
}
