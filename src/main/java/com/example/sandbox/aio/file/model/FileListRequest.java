package com.example.sandbox.aio.file.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * AIO 目录列表请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code FileListRequest}（{@code POST /v1/file/list}）。
 * 仅 {@code path} 必填，其余均为可选过滤/排序字段，为空时使用 AIO 默认值。
 * 字段完整保留以便调用方按需启用，无需再查文档。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileListRequest(
        @JsonProperty("path") String path,
        @JsonProperty("recursive") Boolean recursive,
        @JsonProperty("show_hidden") Boolean showHidden,
        @JsonProperty("file_types") List<String> fileTypes,
        @JsonProperty("max_depth") Integer maxDepth,
        @JsonProperty("include_size") Boolean includeSize,
        @JsonProperty("include_permissions") Boolean includePermissions,
        @JsonProperty("sort_by") String sortBy,
        @JsonProperty("sort_desc") Boolean sortDesc
) {
}
