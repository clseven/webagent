package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.core.AioApiException;
import com.example.sandbox.aio.node.model.NodeExecuteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 连接 Agent 浏览器工具与 AIO 沙箱中隐藏的 Playwright 运行时。
 *
 * <p>该服务构造固定连接包装代码、调用 {@code /v1/nodejs/execute}，并提取一份
 * 结构化结果。沙箱内运行时自行解析 Chrome 的内部 CDP 地址，避免使用仅供宿主机访问
 * 的端口映射地址。模型提供的代码只能接收 Playwright {@code page} 参数，无法获得
 * CDP 地址。</p>
 */
@Service
public class BrowserAgentRuntimeService {

    /** 新 AIO 沙箱启动时安装的 ESM 模块绝对 URI。 */
    private static final String RUNTIME_MODULE_URI =
            "file:///home/gem/.runtime/browser-agent/browser-agent.mjs";

    /** 用于从附带控制台输出中分隔最终工具结果的标记。 */
    private static final String RESULT_MARKER = "__BROWSER_AGENT_RESULT__";

    /** 第一版允许模型提供的 JavaScript 代码最大长度。 */
    private static final int MAX_CODE_LENGTH = 12_000;

    /**
     * 执行前拒绝明显的越权操作和生命周期操作。
     *
     * <p>此校验属于可用性防护，不是安全边界；真正的隔离边界仍然是 AIO 沙箱。
     * 运行时包装层统一管理依赖和浏览器生命周期，因此拒绝加载依赖、访问 Node
     * 全局对象、动态生成代码、创建 Playwright 连接以及关闭页面或上下文。</p>
     */
    private static final List<Pattern> FORBIDDEN_CODE_PATTERNS = List.of(
            Pattern.compile("\\bimport\\s*\\(|\\bimport\\s+.+\\s+from\\s+",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brequire\\s*\\(|\\b(process|globalThis)\\s*\\.|\\beval\\s*\\("
                            + "|\\bnew\\s+Function\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\.\\s*(close|newPage|newContext|launch|connectOverCDP)\\s*\\(",
                    Pattern.CASE_INSENSITIVE)
    );

    /** 用于将模型代码安全嵌入 JavaScript 的 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 Browser Agent 运行时桥接服务。
     *
     * @param objectMapper 应用统一使用的 JSON 序列化器
     */
    public BrowserAgentRuntimeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 通过固定运行时脚本检查当前 AIO 浏览器页面。
     *
     * @param client       当前会话的 AIO 客户端
     * @param maxElements  最多返回的可见交互元素数量
     * @param maxTextChars 最多返回的页面可见文本字符数
     * @return 紧凑的 JSON 页面快照
     * @throws AioApiException 解析内部 CDP 地址、建立连接、执行 Node.js 或提取结果失败时抛出；
     *                         此处不重试，因为下一次工具调用应重新观察最新页面状态
     */
    public String inspect(AioClient client, int maxElements, int maxTextChars) {
        String script = """
                const main = async () => {
                  const runtime = await import(%s);
                  const result = await runtime.inspect({
                    maxElements: %d,
                    maxTextChars: %d
                  });
                  console.log(%s + JSON.stringify(result));
                };
                main().catch((error) => {
                  console.error(error && error.stack ? error.stack : String(error));
                  process.exitCode = 1;
                });
                """.formatted(
                json(RUNTIME_MODULE_URI),
                maxElements,
                maxTextChars,
                json(RESULT_MARKER));
        return executeAndExtract(client, script, 30);
    }

    /**
     * 在当前页面上执行模型生成的 Playwright 异步函数体。
     *
     * <p>传入代码可以使用 {@code page} 变量，并应返回可被 JSON 序列化的值。
     * 包装层负责建立和断开 CDP 连接。加载依赖、访问 Node 进程、创建浏览器以及关闭页面等
     * 明显违反运行时职责边界的操作会直接被拒绝且不重试。</p>
     *
     * @param client  当前会话的 AIO 客户端
     * @param code    使用预绑定 {@code page} 的 JavaScript 异步函数体
     * @param timeout Node.js 执行超时秒数
     * @return 经 JSON 序列化的返回值
     * @throws IllegalArgumentException 代码为空、过长或违反运行时职责约束时抛出
     * @throws AioApiException 解析内部 CDP 地址、建立连接、执行 Node.js 或提取结果失败时抛出
     */
    public String execute(AioClient client, String code, int timeout) {
        validateCode(code);
        String script = """
                const main = async () => {
                  const runtime = await import(%s);
                  const connection = await runtime.connectActivePage();
                  try {
                    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
                    const task = new AsyncFunction("page", "\\"use strict\\";\\n" + %s);
                    const result = await task(connection.page);
                    console.log(%s + JSON.stringify(result === undefined ? null : result));
                  } finally {
                    await connection.browser.close();
                  }
                };
                main().catch((error) => {
                  console.error(error && error.stack ? error.stack : String(error));
                  process.exitCode = 1;
                });
                """.formatted(
                json(RUNTIME_MODULE_URI),
                json(code),
                json(RESULT_MARKER));
        return executeAndExtract(client, script, timeout);
    }

    /**
     * 执行生成的 Node.js 代码并提取带标记的 JSON 结果。
     *
     * @param client  当前会话的 AIO 客户端
     * @param script  完整的 JavaScript 包装脚本
     * @param timeout 执行超时秒数
     * @return 带标记的 JSON 结果
     * @throws AioApiException 执行失败或没有产生带标记结果时抛出
     */
    private String executeAndExtract(AioClient client, String script, int timeout) {
        NodeExecuteResult result = client.node().execute(script, timeout);
        if (result == null) {
            throw new AioApiException("Browser Agent 没有返回执行结果");
        }
        if (!"ok".equalsIgnoreCase(result.getStatus()) || result.getExitCode() != 0) {
            String error = result.getStderr();
            if (error == null || error.isBlank()) {
                error = "status=" + result.getStatus() + ", exitCode=" + result.getExitCode();
            }
            throw new AioApiException("Browser Agent 执行失败: " + error.trim());
        }

        String output = collectOutput(result);
        int markerIndex = output.lastIndexOf(RESULT_MARKER);
        if (markerIndex < 0) {
            throw new AioApiException("Browser Agent 未返回结构化结果");
        }
        String value = output.substring(markerIndex + RESULT_MARKER.length()).trim();
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline).trim() : value;
    }

    /**
     * 合并 Node.js API 返回的普通标准输出和结构化流输出。
     *
     * <p>不同版本的 AIO 镜像可能把 {@code console.log} 文本放在任一字段中。
     * 即使内容重复也不会影响结果，因为提取逻辑始终使用最后一个结果标记。</p>
     *
     * @param result Node.js 执行结果
     * @return 合并后的文本输出
     */
    private String collectOutput(NodeExecuteResult result) {
        StringBuilder output = new StringBuilder();
        if (result.getStdout() != null && !result.getStdout().isBlank()) {
            output.append(result.getStdout()).append('\n');
        }
        if (result.getOutputs() != null) {
            for (var item : result.getOutputs()) {
                Object text = item.get("text");
                if (text != null) {
                    output.append(text).append('\n');
                }
            }
        }
        return output.toString();
    }

    /**
     * 按第一版运行时职责约束校验模型提供的代码。
     *
     * @param code 模型提供的 JavaScript 函数体
     * @throws IllegalArgumentException 代码为空、过长或包含禁止操作时抛出
     */
    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("code 不能超过 " + MAX_CODE_LENGTH + " 个字符");
        }
        for (Pattern pattern : FORBIDDEN_CODE_PATTERNS) {
            if (pattern.matcher(code).find()) {
                throw new IllegalArgumentException(
                        "code 只能操作预先提供的 page，不能加载依赖、访问 Node 进程、创建或关闭浏览器页面");
            }
        }
    }

    /**
     * 将 Java 字符串序列化为安全的 JavaScript 字符串字面量。
     *
     * @param value 待序列化的值
     * @return JSON 字符串字面量
     * @throws AioApiException 序列化意外失败时抛出
     */
    private String json(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AioApiException("Browser Agent 参数序列化失败", e);
        }
    }
}
