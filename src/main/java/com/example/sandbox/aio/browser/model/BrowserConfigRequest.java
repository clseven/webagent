package com.example.sandbox.aio.browser.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 浏览器配置请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code BrowserConfigRequest}（{@code POST /v1/browser/config}）。
 * {@code resolution} 可选，OpenAPI 允许值如 {@code 1920x1080}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrowserConfigRequest(
        @JsonProperty("resolution") String resolution
) {
}
