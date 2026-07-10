package com.example.sandbox.aio.shell.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO Shell 会话查看请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code ShellViewRequest}（{@code POST /v1/shell/view}）。
 * 获取指定 Shell 会话当前的输出，{@code id} 必填。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShellViewRequest(
        @JsonProperty("id") String id
) {
}
