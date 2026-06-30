package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.BrowserAgentRuntimeService;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在当前 AIO 浏览器页面上执行模型生成的 Playwright 操作。
 *
 * <p>模型只需提供 JavaScript 异步函数体。CDP 地址解析、Playwright 加载、页面绑定、
 * 结果序列化和连接清理由 {@link BrowserAgentRuntimeService} 统一负责。</p>
 */
@Component
public class BrowserExecuteTool implements Tool {

    /** 工具执行日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(BrowserExecuteTool.class);

    /** 暴露给模型进行函数调用的工具名称。 */
    private static final String NAME = "browser_execute";

    /** 默认执行超时秒数。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 用于获取当前 Agent 会话对应的 AIO 客户端。 */
    private final SandboxClientFactory factory;

    /** 通过隐藏的 Browser Agent 运行时执行受约束的模型代码。 */
    private final BrowserAgentRuntimeService runtimeService;

    /**
     * 创建 Playwright 浏览器执行工具。
     *
     * @param factory        按会话获取 AIO 客户端的工厂
     * @param runtimeService 隐藏的 Browser Agent 运行时桥接服务
     */
    public BrowserExecuteTool(SandboxClientFactory factory,
                              BrowserAgentRuntimeService runtimeService) {
        this.factory = factory;
        this.runtimeService = runtimeService;
    }

    /**
     * 向模型描述预绑定的 {@code page} API 和生命周期约束。
     *
     * @return {@code browser_execute} 的函数调用定义
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", Map.of(
                "type", "string",
                "minLength", 1,
                "description", """
                        要执行的 JavaScript 代码。可用全局变量：page（当前页面）。
                        可以使用 page.goto、page.evaluate、page.locator 等 Playwright API。
                        不要 require/import，不要新建或关闭 page/context，不要访问 process。
                        最后 return 一个可 JSON 序列化的简短结果。
                        """
        ));
        properties.put("timeout_seconds", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 100,
                "default", DEFAULT_TIMEOUT_SECONDS,
                "description", "整段 Playwright 操作的超时秒数，默认 30，最大 100。"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("code"),
                "additionalProperties", false
        );
        String description = """
                在浏览器中执行 Playwright JavaScript 代码。代码运行在 page 全局上下文中，
                可以使用 page.goto、page.evaluate、page.locator 等 Playwright API。
                """;
        return new ToolDefinition(NAME, description, parameters, "AIO");
    }

    /**
     * 执行传入的 Playwright 函数体并返回其 JSON 结果。
     *
     * @param sessionId 当前 Agent 会话 ID
     * @param arguments 必填代码和可选超时参数
     * @return 结构化结果或可读的错误信息
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        Object codeValue = arguments != null ? arguments.get("code") : null;
        if (!(codeValue instanceof String code) || code.isBlank()) {
            return "错误：code 不能为空";
        }
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutValue = arguments.get("timeout_seconds");
        if (timeoutValue instanceof Number number) {
            timeout = number.intValue();
        }
        if (timeout < 1 || timeout > 100) {
            return "错误：timeout_seconds 必须在 1 到 100 之间";
        }

        try {
            String result = runtimeService.execute(
                    factory.getAioClient(sessionId), code, timeout);
            return "Playwright 执行结果:\n" + result;
        } catch (Exception e) {
            log.error("Playwright 浏览器操作失败", e);
            return "错误：Playwright 浏览器操作失败 - " + e.getMessage();
        }
    }
}
