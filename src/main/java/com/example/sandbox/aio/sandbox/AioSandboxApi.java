package com.example.sandbox.aio.sandbox;

import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.sandbox.model.AioSandboxContext;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 封装 AIO Sandbox 环境和运行时包信息接口。
 */
public class AioSandboxApi {

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /** 用于将顶层 Sandbox 响应转换为类型模型。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 Sandbox API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioSandboxApi(AioHttpClient http) {
        this.http = http;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取 Sandbox 环境上下文。
     *
     * @return Sandbox 上下文
     */
    public AioSandboxContext getContext() {
        return objectMapper.convertValue(http.getMap("/v1/sandbox"), AioSandboxContext.class);
    }

    /**
     * 查询 Sandbox 中已安装的 Node.js 包。
     *
     * @return AIO 完整响应
     */
    public java.util.Map<String, Object> getNodePackages() {
        return http.getMap("/v1/sandbox/packages/nodejs");
    }
}
