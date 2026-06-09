package com.example.sandbox.web.model.request;

/**
 * 知识库检索请求
 *
 * @author example
 * @date 2026/05/31
 */
public class SearchRequest {

    private String query;
    private int topK = 5;

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
}
