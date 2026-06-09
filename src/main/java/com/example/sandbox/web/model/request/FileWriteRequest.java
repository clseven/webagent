package com.example.sandbox.web.model.request;

/**
 * 文件写入请求
 *
 * @author example
 * @date 2026/05/14
 */
public class FileWriteRequest {

    /**
     * 文件路径
     */
    private String path;

    /**
     * 文件内容
     */
    private String content;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
