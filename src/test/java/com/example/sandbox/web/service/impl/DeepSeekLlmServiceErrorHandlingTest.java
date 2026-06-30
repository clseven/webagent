package com.example.sandbox.web.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

    /** 验证最终 API 请求摘要会完整打印最新文本消息，不再只显示前 100 个字符。 */
    @Test
    void requestSummaryLogsFullLatestTextMessage() throws Exception {
        String latestContent = "截图成功！文件路径: /home/gem/temp/browser_screenshot_123.png，大小: 38823 bytes\n\n"
                + "![截图](http://localhost:8081/api/sessions/s1/files/download?path=%2Fhome%2Fgem%2Ftemp%2Fbrowser_screenshot_123.png)\n\n"
                + "如果需要理解截图内容，请继续调用 view_image，参数 path 使用: /home/gem/temp/browser_screenshot_123.png";

        Logger logger = (Logger) LoggerFactory.getLogger(BaseLlmServiceImpl.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = attachLogCapture(logger, Level.INFO);
        try {
            invokeLogRequestBody(new TestLlmService(), Map.of(
                    "model", "agnes-2.0-flash",
                    "messages", List.of(Map.of(
                            "role", "tool",
                            "content", latestContent
                    )),
                    "tools", List.of(Map.of("type", "function"))
            ));

            String logMessage = latestRequestLog(appender);
            assertThat(logMessage)
                    .contains("【LLM 请求摘要】")
                    .contains("latestRole=tool")
                    .contains("latestContentChars=" + latestContent.length())
                    .contains("latestContentFull=\"" + latestContent + "\"")
                    .doesNotContain("latest=tool(")
                    .doesNotContain("...");
        } finally {
            detachLogCapture(logger, appender, originalLevel);
        }
    }

    /** 验证业务层消息计数降到 DEBUG，避免 INFO 中出现两条容易混淆的请求日志。 */
    @Test
    void requestPreparationCountUsesDebugLevel() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(BaseLlmServiceImpl.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = attachLogCapture(logger, Level.DEBUG);
        try {
            Method method = BaseLlmServiceImpl.class.getDeclaredMethod("logRequest", int.class, int.class);
            method.setAccessible(true);
            method.invoke(new TestLlmService(), 5, 26);

            ILoggingEvent event = appender.list.stream()
                    .filter(logEvent -> logEvent.getFormattedMessage().contains("businessMessages=5"))
                    .findFirst()
                    .orElseThrow();

            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("【LLM 请求准备】")
                    .contains("businessMessages=5")
                    .contains("tools=26");
        } finally {
            detachLogCapture(logger, appender, originalLevel);
        }
    }

    /** 验证多模态请求摘要只打印结构信息，不把图片 base64 写进日志。 */
    @Test
    void requestSummaryDescribesContentPartsWithoutImagePayload() throws Exception {
        String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB";

        Logger logger = (Logger) LoggerFactory.getLogger(BaseLlmServiceImpl.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = attachLogCapture(logger, Level.INFO);
        try {
            invokeLogRequestBody(new TestLlmService(), Map.of(
                    "model", "vision-model",
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", "请理解截图"),
                                    Map.of("type", "image_url", "image_url", Map.of(
                                            "url", "data:image/png;base64," + base64
                                    ))
                            )
                    )),
                    "tools", List.of()
            ));

            String logMessage = latestRequestLog(appender);
            assertThat(logMessage)
                    .contains("【LLM 请求摘要】")
                    .contains("latestRole=user")
                    .contains("hasContentParts=true")
                    .contains("contentParts=2")
                    .contains("contentPartTypes=[text, image_url]")
                    .contains("imageParts=1")
                    .contains("imageUrlKinds=[data-url]");
            assertThat(logMessage).doesNotContain(base64);
        } finally {
            detachLogCapture(logger, appender, originalLevel);
        }
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
     * 反射调用请求摘要日志方法，避免为了测试暴露生产 API。
     *
     * @param service     被测 LLM 服务
     * @param requestBody 即将被摘要打印的 LLM 请求体
     */
    private static void invokeLogRequestBody(BaseLlmServiceImpl service, Map<String, Object> requestBody)
            throws Exception {
        Method method = BaseLlmServiceImpl.class.getDeclaredMethod("logRequestBody", Map.class);
        method.setAccessible(true);
        method.invoke(service, requestBody);
    }

    /**
     * 为 BaseLlmServiceImpl logger 挂载内存日志收集器。
     *
     * @param logger 要捕获的 logger
     * @param level  测试期间使用的日志级别
     * @return 已启动的日志收集器
     */
    private static ListAppender<ILoggingEvent> attachLogCapture(Logger logger, Level level) {
        logger.setLevel(level);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 移除测试日志收集器，并恢复 logger 原始级别。
     *
     * @param logger        被捕获的 logger
     * @param appender      测试挂载的日志收集器
     * @param originalLevel 测试前的日志级别
     */
    private static void detachLogCapture(Logger logger, ListAppender<ILoggingEvent> appender, Level originalLevel) {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    /**
     * 读取最近一条 LLM 请求相关日志。
     *
     * @param appender 日志收集器
     * @return 格式化后的日志消息
     */
    private static String latestRequestLog(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("LLM 请求"))
                .reduce((first, second) -> second)
                .orElse("");
    }

    /**
     * 测试专用 LLM 服务，只复用父类日志逻辑，不发起真实网络请求。
     */
    private static final class TestLlmService extends BaseLlmServiceImpl {

        /**
         * 使用本地占位配置创建测试服务实例。
         */
        private TestLlmService() {
            super("http://localhost", "test-api-key", "test-model", new ObjectMapper());
        }
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
