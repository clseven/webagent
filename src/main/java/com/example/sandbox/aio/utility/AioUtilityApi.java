package com.example.sandbox.aio.utility;

import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.utility.model.ConvertToMarkdownRequest;

import java.util.Map;

/**
 * 封装 AIO 通用转换辅助接口。
 */
public class AioUtilityApi {

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /**
     * 创建 Utility API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioUtilityApi(AioHttpClient http) {
        this.http = http;
    }

    /**
     * 将 URL 或文件 URI 转换为 Markdown。
     *
     * @param uri 目标 URI
     * @return Markdown 内容；服务端失败时返回兼容的错误文本
     */
    public String convertToMarkdown(String uri) {
        Map<String, Object> response = http.postMap(
                "/v1/util/convert_to_markdown", new ConvertToMarkdownRequest(uri));
        if (response != null && Boolean.TRUE.equals(response.get("success"))) {
            Object data = response.get("data");
            return data != null ? data.toString() : "";
        }
        return "转换失败：" + (response != null ? response.get("message") : "未知错误");
    }
}
