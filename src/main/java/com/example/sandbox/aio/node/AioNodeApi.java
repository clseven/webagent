package com.example.sandbox.aio.node;

import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.node.model.NodeExecuteResult;

import java.util.HashMap;
import java.util.Map;

/**
 * 封装 AIO Node.js 运行时接口。
 */
public class AioNodeApi {

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /**
     * 创建 Node.js API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioNodeApi(AioHttpClient http) {
        this.http = http;
    }

    /**
     * 在 AIO 临时 Node.js 环境中执行 JavaScript。
     *
     * @param code    JavaScript 代码
     * @param timeout 超时秒数，范围由 OpenAPI 限制为 1 到 300
     * @return Node.js 执行结果
     */
    public NodeExecuteResult execute(String code, int timeout) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("timeout", timeout);
        return http.postData("/v1/nodejs/execute", body, NodeExecuteResult.class);
    }

    /**
     * 查询 Node.js 运行时信息。
     *
     * @return AIO 完整响应
     */
    public Map<String, Object> getInfo() {
        return http.getMap("/v1/nodejs/info");
    }
}
