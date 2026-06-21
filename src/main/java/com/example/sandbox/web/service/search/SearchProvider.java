package com.example.sandbox.web.service.search;

/**
 * 搜索服务提供者接口。
 * <p>
 * 定义统一的搜索抽象，便于接入不同的搜索引擎（DuckDuckGo、百度、Bing 等）。
 * </p>
 */
public interface SearchProvider {

    /**
     * 获取提供者名称，用于日志和错误标识。
     */
    String getName();

    /**
     * 执行搜索并返回格式化结果。
     *
     * @param query 搜索关键词
     * @return 格式化的搜索结果文本，或以 "搜索失败：" 开头的错误信息
     */
    String search(String query);
}
