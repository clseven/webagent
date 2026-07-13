package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.service.enhance.RankedChunk;
import com.example.sandbox.web.service.enhance.RawChunk;
import com.example.sandbox.web.service.enhance.RerankResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用本地 HTTP 服务验证 DeepSeek 重排协议和降级行为。
 */
class DeepSeekRerankServiceImplTest {

    /** JSON 序列化工具。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 每个测试独占的本地模拟服务。 */
    private HttpServer server;

    /**
     * 测试结束后关闭模拟服务，避免端口和线程泄漏。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证请求使用独立 Flash 模型并按模型顺序映射结果。
     */
    @Test
    void reranksWithIndependentFlashModel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        DeepSeekRerankServiceImpl service = createService(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[{\\\"index\\\":1,\\\"score\\\":0.96},{\\\"index\\\":0,\\\"score\\\":0.71}]}\"}}]}");
        }, 12, 10);

        RerankResult result = service.rerank("Spring Boot", List.of(
                new RawChunk(1L, 0, "Java 集合", 0.8f),
                new RawChunk(1L, 1, "Spring Boot 应用", 0.7f)));

        assertThat(result.reranked()).isTrue();
        assertThat(result.chunks()).extracting(RankedChunk::chunkIndex).containsExactly(1, 0);
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(sent.has("response_format")).isFalse();
        assertThat(sent.path("messages").path(0).path("content").asText())
                .contains("\"results\"")
                .contains("禁止返回 text");
        assertThat(requestBody.get()).doesNotContain("deepseek-v4-pro", "tools");
    }

    /**
     * 验证兼容模型偶尔返回的完整顶层数组，避免协议轻微漂移导致无谓降级。
     */
    @Test
    void acceptsCompleteTopLevelArrayResponse() throws Exception {
        DeepSeekRerankServiceImpl service = createService(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"[{\\\"index\\\":1,\\\"score\\\":0.97},{\\\"index\\\":0,\\\"score\\\":0.61}]\"}}]}"),
                12, 10);

        RerankResult result = service.rerank("q", List.of(
                new RawChunk(1L, 0, "model-first", 0.1f),
                new RawChunk(1L, 1, "vector-first", 0.9f)));

        assertThat(result.reranked()).isTrue();
        assertThat(result.chunks()).extracting(RankedChunk::chunkIndex).containsExactly(0, 1);
    }

    /**
     * 验证只把向量分数最高的有限候选发送给模型。
     */
    @Test
    void limitsCandidatesBeforeCallingDeepSeek() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        DeepSeekRerankServiceImpl service = createService(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[{\\\"index\\\":0,\\\"score\\\":0.9}]}\"}}]}");
        }, 2, 10);

        service.rerank("q", List.of(
                new RawChunk(1L, 0, "low", 0.1f),
                new RawChunk(1L, 1, "high", 0.9f),
                new RawChunk(1L, 2, "middle", 0.5f)));

        JsonNode sent = objectMapper.readTree(requestBody.get());
        String userJson = sent.path("messages").path(1).path("content").asText();
        assertThat(userJson).contains("high", "middle").doesNotContain("low");
    }

    /**
     * 验证非法 JSON 不重试并按向量分数降级。
     */
    @Test
    void fallsBackToVectorScoresForInvalidJson() throws Exception {
        DeepSeekRerankServiceImpl service = createService(
                exchange -> respond(exchange, 200,
                        "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}"),
                12, 10);

        RerankResult result = service.rerank("q", List.of(
                new RawChunk(1L, 0, "low", 0.1f),
                new RawChunk(1L, 1, "high", 0.9f)));

        assertThat(result.reranked()).isFalse();
        assertThat(result.chunks()).extracting(RankedChunk::chunkIndex).containsExactly(1, 0);
    }

    /**
     * 验证 HTTP 超时后返回向量结果，不让检索无限等待。
     */
    @Test
    void fallsBackAfterTimeout() throws Exception {
        DeepSeekRerankServiceImpl service = createService(exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        }, 12, 1);

        long started = System.nanoTime();
        RerankResult result = service.rerank("q", List.of(
                new RawChunk(1L, 0, "low", 0.1f),
                new RawChunk(1L, 1, "high", 0.9f)));

        assertThat(result.reranked()).isFalse();
        assertThat(result.chunks()).extracting(RankedChunk::chunkIndex).containsExactly(1, 0);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(2));
    }

    /**
     * 创建指向本地模拟服务的 DeepSeek 重排器。
     *
     * @param handler 每次 HTTP 请求的响应逻辑
     * @param maxCandidates 最大候选数
     * @param timeoutSeconds 超时秒数
     * @return 可直接调用的重排服务
     */
    private DeepSeekRerankServiceImpl createService(ExchangeHandler handler,
                                                     int maxCandidates,
                                                     int timeoutSeconds) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler::handle);
        server.start();

        RagConfigProperties properties = new RagConfigProperties();
        RagConfigProperties.Enhancement.Rerank config = properties.getEnhancement().getRerank();
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey("test-key");
        config.setModel("deepseek-v4-flash");
        config.setMaxCandidates(maxCandidates);
        config.setTimeoutSeconds(timeoutSeconds);
        config.setTopK(8);
        return new DeepSeekRerankServiceImpl(properties, objectMapper);
    }

    /**
     * 返回普通 JSON HTTP 响应。
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 本地测试服务器的请求处理函数。 */
    @FunctionalInterface
    private interface ExchangeHandler {
        /** 处理一次模拟 DeepSeek HTTP 请求。 */
        void handle(HttpExchange exchange) throws IOException;
    }
}
