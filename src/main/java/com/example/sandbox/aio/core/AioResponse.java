package com.example.sandbox.aio.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * AIO JSON 接口的通用响应信封。
 *
 * @param success 操作是否成功
 * @param message 服务端返回的结果说明
 * @param data    具体接口返回的数据
 * @param <T>     数据类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AioResponse<T>(boolean success, String message, T data) {
}
