package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import reactor.test.StepVerifier;

/**
 * 使用本地 HTTP 服务验证 DeepSeek 调用层的状态码、重试和流式错误行为。
 */
class DeepSeekLlmServiceErrorHandlingTest {

    /** 每个测试独占的本地模拟 DeepSeek 服务。 */
    private HttpServer server;

    /** 测试结束后关闭本地 HTTP 服务，避免端口和线程泄漏。 */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 验证非流式 429 只请求一次，并将明确错误抛给上层。 */
    @Test
    void doesNotRetryRateLimitResponse() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        DeepSeekLlmServiceImpl service = createService(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 429, "{\"error\":{\"message\":\"rate limit\"}}");
        });

        assertThatThrownBy(() -> service.chatWithSystem(
                "system", List.of(ChatMessage.userMessage("hello"))))
                .isInstanceOf(LlmApiException.class)
                .hasMessage("DeepSeek 请求过于频繁，请稍后再试。");
        assertThat(requests).hasValue(1);
    }

    /** 验证非流式 500 最多重试两次，并可在第三次请求成功。 */
    @Test
    void retriesTemporaryServerErrorsTwice() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        DeepSeekLlmServiceImpl service = createService(exchange -> {
            int attempt = requests.incrementAndGet();
            if (attempt < 3) {
                respond(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
                return;
            }
            respond(exchange, 200, """
                    {"id":"test","model":"deepseek-chat","choices":[
                      {"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}
                    ],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """);
        });

        String result = service.chatWithSystem(
                "system", List.of(ChatMessage.userMessage("hello")));

        assertThat(result).isEqualTo("ok");
        assertThat(requests).hasValue(3);
    }

    /** 验证流式 429 同样不重试，并通过响应流传递明确错误。 */
    @Test
    void streamDoesNotRetryRateLimitResponse() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        DeepSeekLlmServiceImpl service = createService(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 429, "{\"error\":{\"message\":\"rate limit\"}}");
        });

        StepVerifier.create(service.chatStream(
                        "system", List.of(ChatMessage.userMessage("hello"))))
                .expectErrorMatches(error -> error instanceof LlmApiException
                        && "DeepSeek 请求过于频繁，请稍后再试。".equals(error.getMessage()))
                .verify();

        assertThat(requests).hasValue(1);
    }

    /** 验证流式 500 在尚未输出内容时最多重试两次。 */
    @Test
    void streamRetriesTemporaryServerErrorsBeforeOutputStarts() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        DeepSeekLlmServiceImpl service = createService(exchange -> {
            int attempt = requests.incrementAndGet();
            if (attempt < 3) {
                respond(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
                return;
            }
            respondSse(exchange, """
                    data: {"choices":[{"delta":{"content":"ok"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
        });

        StepVerifier.create(service.chatStream(
                        "system", List.of(ChatMessage.userMessage("hello"))))
                .expectNext("ok")
                .verifyComplete();

        assertThat(requests).hasValue(3);
    }

    /** 验证流式响应开始后遇到异常结束原因时不会重试或重复输出。 */
    @Test
    void streamReportsAbnormalFinishReasonWithoutRetrying() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        DeepSeekLlmServiceImpl service = createService(exchange -> {
            requests.incrementAndGet();
            respondSse(exchange, """
                    data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                    """);
        });

        StepVerifier.create(service.chatStream(
                        "system", List.of(ChatMessage.userMessage("hello"))))
                .expectNext("partial")
                .expectErrorMatches(error -> error instanceof LlmApiException
                        && error.getMessage().contains("达到长度限制"))
                .verify();

        assertThat(requests).hasValue(1);
    }

    /**
     * 创建指向本地模拟服务的 DeepSeek executor。
     *
     * @param handler 每次 HTTP 请求的响应逻辑
     * @return 可直接调用的 DeepSeek 服务
     */
    private DeepSeekLlmServiceImpl createService(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> handler.handle(exchange));
        server.start();

        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getLlm().getExecutor().setApiUrl(
                "http://127.0.0.1:" + server.getAddress().getPort());
        properties.getLlm().getExecutor().setApiKey("test-key");
        properties.getLlm().getExecutor().setModel("deepseek-chat");
        return new DeepSeekLlmServiceImpl(properties, new ObjectMapper());
    }

    /**
     * 返回普通 JSON HTTP 响应。
     *
     * @param exchange 当前 HTTP 交换
     * @param status   HTTP 状态码
     * @param body     JSON 响应体
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 返回 text/event-stream 格式的模拟流式响应。
     *
     * @param exchange 当前 HTTP 交换
     * @param body     完整 SSE 响应体
     */
    private static void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 本地测试服务器的请求处理函数。
     */
    @FunctionalInterface
    private interface ExchangeHandler {
        /**
         * 处理一次模拟 DeepSeek HTTP 请求。
         *
         * @param exchange 当前 HTTP 交换
         */
        void handle(HttpExchange exchange) throws IOException;
    }
}
