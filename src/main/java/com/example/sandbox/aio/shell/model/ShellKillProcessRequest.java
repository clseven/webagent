package com.example.sandbox.aio.shell.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO Shell 进程终止请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code ShellKillProcessRequest}（{@code POST /v1/shell/kill}）。
 * 终止指定 Shell 会话中的进程，{@code id} 必填。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShellKillProcessRequest(
        @JsonProperty("id") String id
) {
}
