package com.example.sandbox.aio.file.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 文件读取请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code FileReadRequest}（{@code POST /v1/file/read}）。
 * 仅 {@code file} 必填，{@code start_line}/{@code end_line} 支持按行范围读取（0 基，end 不含），
 * 用于大文件分页，避免一次性读取撑爆上下文。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileReadRequest(
        @JsonProperty("file") String file,
        @JsonProperty("start_line") Integer startLine,
        @JsonProperty("end_line") Integer endLine,
        @JsonProperty("sudo") Boolean sudo
) {
}
