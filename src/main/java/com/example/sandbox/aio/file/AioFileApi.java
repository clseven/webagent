package com.example.sandbox.aio.file;

import com.example.sandbox.aio.core.AioApiException;
import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.file.model.FileListRequest;
import com.example.sandbox.aio.file.model.FileReadRequest;
import com.example.sandbox.aio.file.model.FileReplaceRequest;
import com.example.sandbox.aio.file.model.FileSearchRequest;
import com.example.sandbox.aio.file.model.FileWriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 封装 AIO File REST API。
 *
 * <p>该类是新 AIO 客户端的文件能力入口，调用 checked-in OpenAPI 中定义的
 * `/v1/file/*` 接口，不在这里混用旧版 shell/base64 文件写入协议。</p>
 *
 * <p>所有请求体均使用 {@code aio.file.model} 下的类型化 record 构造，
 * 字段与可选性直接由 record 定义，无需查 OpenAPI 文档。</p>
 */
public class AioFileApi {

    /** 记录 AIO 文件接口调用中的瞬时失败，便于排查沙箱刚启动时的连接问题。 */
    private static final Logger log = LoggerFactory.getLogger(AioFileApi.class);

    /** 文件写入最大重试次数，仅用于 `/v1/file/write` 的瞬时调用失败。 */
    private static final int WRITE_RETRY_ATTEMPTS = 3;

    /** 文件写入重试基础等待时间，按尝试次数线性递增。 */
    private static final long WRITE_RETRY_BASE_DELAY_MILLIS = 300L;

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
     * 使用 AIO 文件读取接口读取 UTF-8 文本（整文件）。
     *
     * @param path Sandbox 内绝对路径
     * @return 文件内容；响应不含内容时返回空字符串
     * @throws AioApiException 当 AIO 明确返回读取失败时抛出
     */
    public String readText(String path) {
        return readText(path, null, null);
    }

    /**
     * 使用 AIO 文件读取接口读取指定行范围内的 UTF-8 文本。
     *
     * <p>利用 AIO 原生的 {@code start_line}/{@code end_line} 实现服务端分页读取，
     * 大文件只取需要的窗口，不会整本读入内存。</p>
     *
     * @param path      Sandbox 内绝对路径
     * @param startLine 起始行（0 基）；为 null 时从头读
     * @param endLine   结束行（不含）；为 null 时读到文件尾
     * @return 文件内容；响应不含内容时返回空字符串
     * @throws AioApiException 当 AIO 明确返回读取失败时抛出
     */
    public String readText(String path, Integer startLine, Integer endLine) {
        Map<String, Object> response = http.postMap("/v1/file/read",
                new FileReadRequest(path, startLine, endLine, null));
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
        return writeWithRetry(path, content, "utf-8");
    }

    /**
     * 使用 Base64 写入二进制文件。
     *
     * @param path    Sandbox 内绝对路径
     * @param content 文件字节
     * @return 写入是否成功
     */
    public boolean writeBytes(String path, byte[] content) {
        return writeWithRetry(path, Base64.getEncoder().encodeToString(content), "base64");
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
        return http.getBytes("/v1/file/download?path={path}", Map.of("path", path), MediaType.APPLICATION_OCTET_STREAM);
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
        return http.postMap("/v1/file/replace",
                new FileReplaceRequest(file, oldStr, newStr, null));
    }

    /**
     * 使用正则表达式搜索文件。
     *
     * @param file  Sandbox 内文件路径
     * @param regex 正则表达式
     * @return AIO 完整响应
     */
    public Map<String, Object> search(String file, String regex) {
        return http.postMap("/v1/file/search", new FileSearchRequest(file, regex, null));
    }

    /**
     * 调用 AIO 结构化文件编辑器。
     *
     * <p>参数由 Anthropic 工具协议定义，调用方按其规范构造，这里直接透传。</p>
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
        FileListRequest body = new FileListRequest(path, recursive, showHidden, null,
                maxDepth, includeSize, null, sortBy, sortDesc);
        return http.postMap("/v1/file/list", body);
    }

    /**
     * 判断文件是否已存在。
     *
     * <p>沙箱无单独 stat 端点，这里列目标文件的父目录（非递归），在返回项里按文件名匹配。
     * 任何异常或无法解析都保守返回 false（当作新建），避免误拦。</p>
     *
     * @param path Sandbox 内绝对路径
     * @return true 表示文件已存在
     */
    @SuppressWarnings("unchecked")
    public boolean exists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = normalized.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : normalized.substring(0, slash);
        String name = normalized.substring(slash + 1);
        if (name.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> response = list(parent, false, true, 1, false, "name", false);
            if (!success(response)) {
                return false;
            }
            Object data = response.get("data");
            List<Map<String, Object>> entries = extractEntries(data);
            for (Map<String, Object> entry : entries) {
                Object entryName = entry.get("name");
                if (entryName != null && name.equals(entryName.toString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("AIO 判断文件存在失败，保守当作新建: path={}, reason={}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 从 `/v1/file/list` 的 data 字段提取目录项列表，兼容 data 直接为数组或 data.files/data.entries 的形态。
     *
     * @param data list 响应的 data 字段
     * @return 目录项列表，无法解析时为空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractEntries(Object data) {
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        if (data instanceof Map<?, ?> map) {
            for (String key : new String[]{"files", "entries", "items", "list"}) {
                Object value = ((Map<String, Object>) map).get(key);
                if (value instanceof List<?> list) {
                    return (List<Map<String, Object>>) list;
                }
            }
        }
        return List.of();
    }

    /**
     * 使用 AIO `/v1/file/write` 写入文件并处理瞬时失败。
     *
     * <p>这里只重试连接被中止、响应提前关闭、HTTP 客户端异常以及 AIO 返回 success=false
     * 这类沙箱服务刚启动时常见的瞬时问题；重试耗尽后返回 false，由上层记录失败路径。
     * 本方法不切换到 multipart upload，避免同一个写入行为走两套不同协议。</p>
     *
     * @param path     Sandbox 内绝对路径
     * @param content  已按 encoding 准备好的内容
     * @param encoding OpenAPI 定义的写入编码，例如 utf-8 或 base64
     * @return 写入成功返回 true，重试耗尽仍失败返回 false
     */
    private boolean writeWithRetry(String path, String content, String encoding) {
        FileWriteRequest body = new FileWriteRequest(path, content, encoding, null, null, null, null);
        for (int attempt = 1; attempt <= WRITE_RETRY_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> response = http.postMap("/v1/file/write", body);
                if (success(response)) {
                    return true;
                }
                log.warn("AIO 文件写入返回失败: path={}, attempt={}, response={}", path, attempt, response);
            } catch (Exception e) {
                log.warn("AIO 文件写入调用异常: path={}, attempt={}, reason={}", path, attempt, e.getMessage());
            }
            sleepBeforeRetry(attempt);
        }
        return false;
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

    /**
     * 写入重试前短暂等待。
     *
     * @param attempt 当前尝试序号
     */
    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(WRITE_RETRY_BASE_DELAY_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 AIO 文件写入重试时被中断", e);
        }
    }
}
