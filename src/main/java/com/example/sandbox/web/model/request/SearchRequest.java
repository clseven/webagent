package com.example.sandbox.web.model.request;

/**
 * 知识库检索请求
 *
 * @author example
 * @date 2026/05/31
 */
public class SearchRequest {

    /** 用户输入的检索问题。 */
    private String query;
    /** 最大返回条数。 */
    private int topK = 5;
    /** 本次最低相关度；不传时由后端使用默认值。 */
    private Float minScore;

    public SearchRequest() {
    }

    public SearchRequest(String query, int topK) {
        this.query = query;
        this.topK = topK;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    /**
     * 获取本次最低相关度。
     *
     * @return 0 到 1 的相关度；未传时为 null
     */
    public Float getMinScore() {
        return minScore;
    }

    /**
     * 设置本次最低相关度，后端过滤器会将越界值收敛到 0 到 1。
     *
     * @param minScore 最低相关度
     */
    public void setMinScore(Float minScore) {
        this.minScore = minScore;
    }
}
