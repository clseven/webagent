package com.example.sandbox.aio.utility.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 资源转 Markdown 请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code UtilConvertToMarkdownRequest}（{@code POST /v1/util/convert_to_markdown}）。
 * {@code uri} 必填，指向要转换的 URL 或文件 URI。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConvertToMarkdownRequest(
        @JsonProperty("uri") String uri
) {
}
