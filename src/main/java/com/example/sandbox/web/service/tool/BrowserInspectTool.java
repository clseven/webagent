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
 * 提供当前 AIO 浏览器页面的紧凑语义快照。
 *
 * <p>该工具是执行 Playwright 操作前后的首选观察手段。它返回可见文本和交互元素元数据，
 * 但不会暴露 CDP 细节，也不允许执行任意页面代码。</p>
 */
@Component
public class BrowserInspectTool implements Tool {

    /** 工具执行日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(BrowserInspectTool.class);

    /** 暴露给模型进行函数调用的工具名称。 */
    private static final String NAME = "browser_inspect";

    /** 单次快照默认返回的最大交互元素数量。 */
    private static final int DEFAULT_MAX_ELEMENTS = 80;

    /** 单次快照默认返回的最大可见文本字符数。 */
    private static final int DEFAULT_MAX_TEXT_CHARS = 8000;

    /** 用于获取当前 Agent 会话对应的 AIO 客户端。 */
    private final SandboxClientFactory factory;

    /** 用于执行固定 Browser Agent 页面检查脚本。 */
    private final BrowserAgentRuntimeService runtimeService;

    /**
     * 创建浏览器语义检查工具。
     *
     * @param factory        按会话获取 AIO 客户端的工厂
     * @param runtimeService 隐藏的 Browser Agent 运行时桥接服务
     */
    public BrowserInspectTool(SandboxClientFactory factory,
                              BrowserAgentRuntimeService runtimeService) {
        this.factory = factory;
        this.runtimeService = runtimeService;
    }

    /**
     * 向模型描述页面检查限制和推荐的浏览器操作流程。
     *
     * @return {@code browser_inspect} 的函数调用定义
     */
    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("max_elements", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 200,
                "default", DEFAULT_MAX_ELEMENTS,
                "description", "最多返回多少个当前可见的交互元素。默认 80；页面很大时可提高。"
        ));
        properties.put("max_text_chars", Map.of(
                "type", "integer",
                "minimum", 1000,
                "maximum", 20000,
                "default", DEFAULT_MAX_TEXT_CHARS,
                "description", "最多返回多少个当前页面可见文本字符。默认 8000。"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of(),
                "additionalProperties", false
        );
        String description = """
                检查当前浏览器页面，返回 URL、标题、可见文本和带 selector 的交互元素清单。
                """;
        return new ToolDefinition(NAME, description, parameters, "AIO");
    }

    /**
     * 执行页面语义检查，并向模型返回紧凑的 JSON。
     *
     * @param sessionId 当前 Agent 会话 ID
     * @param arguments 可选的快照范围限制
     * @return JSON 页面快照或可读的错误信息
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        int maxElements = integerArgument(arguments, "max_elements", DEFAULT_MAX_ELEMENTS);
        int maxTextChars = integerArgument(arguments, "max_text_chars", DEFAULT_MAX_TEXT_CHARS);
        if (maxElements < 1 || maxElements > 200) {
            return "错误：max_elements 必须在 1 到 200 之间";
        }
        if (maxTextChars < 1000 || maxTextChars > 20000) {
            return "错误：max_text_chars 必须在 1000 到 20000 之间";
        }

        try {
            String snapshot = runtimeService.inspect(
                    factory.getAioClient(sessionId), maxElements, maxTextChars);
            return "页面语义快照:\n" + snapshot;
        } catch (Exception e) {
            log.error("浏览器页面检查失败", e);
            return "错误：浏览器页面检查失败 - " + e.getMessage();
        }
    }

    /**
     * 读取可选的数值型工具参数。
     *
     * @param arguments 工具参数映射，允许为 null
     * @param name      参数名称
     * @param fallback  参数缺失时使用的默认值
     * @return 整数参数值
     */
    private int integerArgument(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments != null ? arguments.get(name) : null;
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
