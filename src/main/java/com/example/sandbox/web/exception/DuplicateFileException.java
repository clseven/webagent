package com.example.sandbox.web.exception;

import lombok.Getter;

/**
 * 知识库重复文件异常
 *
 * <p>当用户上传与已有文件同名的文档时抛出，携带已有文档的 ID，
 * 前端可根据该 ID 弹出对话框，让用户选择：替换 / 保留两份 / 跳过。</p>
 */
@Getter
public class DuplicateFileException extends RuntimeException {

    private final String fileName;
    private final Long existingDocId;

    public DuplicateFileException(String fileName, Long existingDocId) {
        super("文件已存在: " + fileName);
        this.fileName = fileName;
        this.existingDocId = existingDocId;
    }

}
