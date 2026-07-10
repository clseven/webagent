package com.example.sandbox.aio.file.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 文件内容替换请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code FileReplaceRequest}（{@code POST /v1/file/replace}）。
 * 将 {@code old_str} 替换为 {@code new_str}，{@code file}/{@code old_str}/{@code new_str} 均必填。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileReplaceRequest(
        @JsonProperty("file") String file,
        @JsonProperty("old_str") String oldStr,
        @JsonProperty("new_str") String newStr,
        @JsonProperty("sudo") Boolean sudo
) {
}
