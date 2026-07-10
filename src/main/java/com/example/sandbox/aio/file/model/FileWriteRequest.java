package com.example.sandbox.aio.file.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 文件写入请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code FileWriteRequest}（{@code POST /v1/file/write}）。
 * 支持文本（{@code encoding="utf-8"}）和二进制（{@code encoding="base64"}）两种写入模式。
 * 除 {@code file}/{@code content} 外均为可选字段，为空时由 AIO 使用默认值。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileWriteRequest(
        @JsonProperty("file") String file,
        @JsonProperty("content") String content,
        @JsonProperty("encoding") String encoding,
        @JsonProperty("append") Boolean append,
        @JsonProperty("leading_newline") Boolean leadingNewline,
        @JsonProperty("trailing_newline") Boolean trailingNewline,
        @JsonProperty("sudo") Boolean sudo
) {
}
