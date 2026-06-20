package com.example.sandbox.web.model.request;

import java.util.List;

/**
 * 批量删除会话请求。
 *
 * <p>只声明客户端希望删除的会话 ID，实际可删除范围由后端根据当前登录用户重新校验。</p>
 */
public class BatchDeleteSessionsRequest {

    /**
     * 待删除的会话 ID 列表。
     */
    private List<String> sessionIds;

    /**
     * 获取待删除的会话 ID 列表。
     *
     * @return 会话 ID 列表，可能为 null
     */
    public List<String> getSessionIds() {
        return sessionIds;
    }

    /**
     * 设置待删除的会话 ID 列表。
     *
     * @param sessionIds 会话 ID 列表
     */
    public void setSessionIds(List<String> sessionIds) {
        this.sessionIds = sessionIds;
    }
}
