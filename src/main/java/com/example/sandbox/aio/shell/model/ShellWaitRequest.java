package com.example.sandbox.aio.shell.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO Shell 会话等待请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code ShellWaitRequest}（{@code POST /v1/shell/wait}）。
 * 等待指定 Shell 会话继续执行，{@code id} 必填，{@code seconds} 可选。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShellWaitRequest(
        @JsonProperty("id") String id,
        @JsonProperty("seconds") Integer seconds
) {
}
