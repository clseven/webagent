package com.example.sandbox.aio.file.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 文件内容搜索请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code FileSearchRequest}（{@code POST /v1/file/search}）。
 * 使用正则表达式在指定文件内搜索，{@code file}/{@code regex} 均必填。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileSearchRequest(
        @JsonProperty("file") String file,
        @JsonProperty("regex") String regex,
        @JsonProperty("sudo") Boolean sudo
) {
}
