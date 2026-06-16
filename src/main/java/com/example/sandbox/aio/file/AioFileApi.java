package com.example.sandbox.aio.file;

import com.example.sandbox.aio.core.AioApiException;
import com.example.sandbox.aio.core.AioHttpClient;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 封装 AIO File REST API。
 */
public class AioFileApi {

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /**
     * 创建 File API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioFileApi(AioHttpClient http) {
        this.http = http;
    }

    /**
     * 使用 AIO 文件读取接口读取 UTF-8 文本。
     *
     * @param path Sandbox 内绝对路径
     * @return 文件内容；响应不含内容时返回空字符串
     * @throws AioApiException 当 AIO 明确返回读取失败时抛出
     */
    public String readText(String path) {
        Map<String, Object> response = http.postMap("/v1/file/read", Map.of("file", path));
        if (response == null || Boolean.FALSE.equals(response.get("success"))) {
            throw new AioApiException("读取 Sandbox 文件失败: " + path);
        }
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map) {
            Object content = map.get("content");
            return content != null ? content.toString() : "";
        }
        return data != null ? data.toString() : "";
    }

    /**
     * 写入 UTF-8 文本文件。
     *
     * @param path    Sandbox 内绝对路径
     * @param content 文本内容
     * @return 写入是否成功
     */
    public boolean writeText(String path, String content) {
        return success(http.postMap("/v1/file/write", Map.of(
                "file", path,
                "content", content,
                "encoding", "utf-8"
        )));
    }

    /**
     * 使用 Base64 写入二进制文件。
     *
     * @param path    Sandbox 内绝对路径
     * @param content 文件字节
     * @return 写入是否成功
     */
    public boolean writeBytes(String path, byte[] content) {
        return success(http.postMap("/v1/file/write", Map.of(
                "file", path,
                "content", Base64.getEncoder().encodeToString(content),
                "encoding", "base64"
        )));
    }

    /**
     * 通过 multipart 上传文件。
     *
     * @param path    Sandbox 内目标路径
     * @param content 文件字节
     * @return 上传是否成功
     */
    public boolean upload(String path, byte[] content) {
        return success(http.postMultipart("/v1/file/upload", path, content));
    }

    /**
     * 下载 Sandbox 文件。
     *
     * @param path Sandbox 内绝对路径
     * @return 文件字节；空响应时为 null
     */
    public byte[] download(String path) {
        String uri = UriComponentsBuilder.fromPath("/v1/file/download")
                .queryParam("path", path)
                .build()
                .toUriString();
        return http.getBytes(uri, MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * 替换文件中的指定文本。
     *
     * @param file   Sandbox 内文件路径
     * @param oldStr 原文本
     * @param newStr 新文本
     * @return AIO 完整响应
     */
    public Map<String, Object> replace(String file, String oldStr, String newStr) {
        return http.postMap("/v1/file/replace", Map.of(
                "file", file,
                "old_str", oldStr,
                "new_str", newStr
        ));
    }

    /**
     * 使用正则表达式搜索文件。
     *
     * @param file  Sandbox 内文件路径
     * @param regex 正则表达式
     * @return AIO 完整响应
     */
    public Map<String, Object> search(String file, String regex) {
        return http.postMap("/v1/file/search", Map.of("file", file, "regex", regex));
    }

    /**
     * 调用 AIO 结构化文件编辑器。
     *
     * @param parameters OpenAPI 定义的编辑参数
     * @return AIO 完整响应
     */
    public Map<String, Object> edit(Map<String, Object> parameters) {
        return http.postMap("/v1/file/str_replace_editor", parameters);
    }

    /**
     * 列出目录内容。
     *
     * @param path        目录路径
     * @param recursive   是否递归
     * @param showHidden  是否显示隐藏项
     * @param maxDepth    最大递归深度
     * @param includeSize 是否包含大小
     * @param sortBy      排序字段
     * @param sortDesc    是否降序
     * @return AIO 完整响应
     */
    public Map<String, Object> list(String path, boolean recursive, boolean showHidden,
                                    Integer maxDepth, boolean includeSize, String sortBy,
                                    boolean sortDesc) {
        Map<String, Object> body = new HashMap<>();
        body.put("path", path);
        body.put("recursive", recursive);
        body.put("show_hidden", showHidden);
        body.put("include_size", includeSize);
        body.put("sort_by", sortBy);
        body.put("sort_desc", sortDesc);
        if (maxDepth != null) {
            body.put("max_depth", maxDepth);
        }
        return http.postMap("/v1/file/list", body);
    }

    /**
     * 判断 AIO 通用响应是否成功。
     *
     * @param response AIO 完整响应
     * @return success 字段为 true 时返回 true
     */
    private boolean success(Map<String, Object> response) {
        return response != null && Boolean.TRUE.equals(response.get("success"));
    }
}
