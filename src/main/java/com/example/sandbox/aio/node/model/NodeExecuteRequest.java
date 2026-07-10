package com.example.sandbox.aio.node.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * AIO Node.js 执行请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code NodeJSExecuteRequest}（{@code POST /v1/nodejs/execute}）。
 * {@code code} 必填；{@code timeout}（1~300 秒）、{@code stdin}、{@code files} 可选。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeExecuteRequest(
        @JsonProperty("code") String code,
        @JsonProperty("timeout") Integer timeout,
        @JsonProperty("stdin") String stdin,
        @JsonProperty("files") Map<String, Object> files
) {
}
