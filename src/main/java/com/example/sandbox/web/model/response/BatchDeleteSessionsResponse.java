package com.example.sandbox.web.model.response;

import java.util.List;

/**
 * 批量删除会话结果。
 *
 * <p>未找到或不属于当前用户的会话统一归入跳过列表，避免批量接口泄露其他用户的会话信息。</p>
 */
public class BatchDeleteSessionsResponse {

    /**
     * 已成功删除的会话 ID。
     */
    private final List<String> deletedSessionIds;

    /**
     * 因不存在或无权访问而跳过的会话 ID。
     */
    private final List<String> skippedSessionIds;

    /**
     * 创建批量删除结果。
     *
     * @param deletedSessionIds 已成功删除的会话 ID
     * @param skippedSessionIds 未删除的会话 ID
     */
    public BatchDeleteSessionsResponse(List<String> deletedSessionIds, List<String> skippedSessionIds) {
        this.deletedSessionIds = List.copyOf(deletedSessionIds);
        this.skippedSessionIds = List.copyOf(skippedSessionIds);
    }

    /**
     * 获取已成功删除的会话 ID。
     *
     * @return 不可变的会话 ID 列表
     */
    public List<String> getDeletedSessionIds() {
        return deletedSessionIds;
    }

    /**
     * 获取未删除的会话 ID。
     *
     * @return 不可变的会话 ID 列表
     */
    public List<String> getSkippedSessionIds() {
        return skippedSessionIds;
    }
}
