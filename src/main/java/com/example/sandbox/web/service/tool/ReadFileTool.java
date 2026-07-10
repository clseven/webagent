package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取文件工具 — 在 AIO 沙箱中按行窗口读取文件内容
 *
 * <h3>用途</h3>
 * <p>让 LLM 能查看沙箱内任意文件的内容，包括用户上传的文件、命令输出、配置和日志。</p>
 *
 * <h3>分页设计</h3>
 * <p>不在内存中整本读取再截断，而是利用 AIO 文件接口原生的 {@code start_line}/{@code end_line}
 * 服务端分页：一次只读一个窗口（默认 2000 行），模型需要后续内容时带 {@code offset} 再调一次。
 * 单行超过 {@link #MAX_LINE_CHARS} 会截断（防 minified/打包产物），单次总输出不超过
 * {@link #MAX_READ_OUTPUT_CHARS}。大文件不会撑爆上下文。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>path — 要读取的文件路径（沙箱内绝对路径）</li>
 *   <li>offset — 起始行（0 基），默认 0</li>
 *   <li>limit — 读取行数，默认 2000，上限 5000</li>
 * </ul>
 */
@Component
public class ReadFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);
    private static final String NAME = "read_file";

    /** 默认每次读取的行数。 */
    private static final int DEFAULT_LIMIT = 2000;

    /** 单次读取行数上限，防止模型一次请求过多。 */
    private static final int MAX_LIMIT = 5000;

    /** 单行字符上限，超过则截断，专门防 minified/打包产物这类一行几万字符的文件。 */
    private static final int MAX_LINE_CHARS = 2000;

    /** 单次输出总字符上限，作为整本的最终兜底。 */
    private static final int MAX_READ_OUTPUT_CHARS = 48_000;

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "要读取的文件路径（沙箱内绝对路径）"
        ));
        properties.put("offset", Map.of(
                "type", "integer",
                "description", "起始行（0 基），默认 0；大文件分页读取时传入上一轮返回的下一行号",
                "default", 0
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "读取行数，默认 2000，上限 5000",
                "default", DEFAULT_LIMIT
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("path"),
                "additionalProperties", false
        );

        return new ToolDefinition(
                NAME,
                """
                        读取沙箱内文件内容，按行窗口返回（默认 2000 行）。
                        大文件请分页读取：传入 offset 翻到后续行；单行过长会被截断。
                        首次读取可不传 offset/limit，看到末尾的翻页提示后再带 offset 继续读。
                        """,
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = readString(arguments.get("path"));
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        int offset = readInt(arguments.get("offset"), 0);
        if (offset < 0) {
            offset = 0;
        }
        int limit = readInt(arguments.get("limit"), DEFAULT_LIMIT);
        if (limit < 1) {
            limit = 1;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }

        try {
            var client = factory.getAioClient(sessionId);
            if (!client.waitForReady()) {
                return "错误：AIO 服务未就绪，请稍后重试";
            }
            // AIO end_line 不含，0 基
            String content = client.files().readText(path, offset, offset + limit);
            return formatWindow(path, content, offset, limit);
        } catch (Exception e) {
            log.warn("读取文件失败: path={} offset={} limit={}", path, offset, limit, e);
            return "读取失败：" + e.getMessage();
        }
    }

    /**
     * 把窗口内容格式化为 observation：单行截断 + 总量上限 + 翻页提示。
     *
     * @param path    文件路径（用于提示）
     * @param content AIO 返回的窗口原文
     * @param offset  本次起始行（0 基）
     * @param limit   本次请求行数
     * @return observation 文本
     */
    private String formatWindow(String path, String content, int offset, int limit) {
        if (content == null || content.isEmpty()) {
            return "文件为空或已超出读取范围：" + path + "（offset=" + offset + "）";
        }

        // split 不保留尾随空串，丢弃文件末尾的空行，避免行号错位
        String[] lines = content.split("\r?\n");
        StringBuilder out = new StringBuilder();
        int total = 0;
        int linesOutput = 0;
        boolean capped = false;

        for (String line : lines) {
            String processed = line.length() > MAX_LINE_CHARS
                    ? line.substring(0, MAX_LINE_CHARS)
                            + " ... [line truncated, " + line.length() + " chars]"
                    : line;
            int added = processed.length() + 1; // +1 for newline
            if (total + added > MAX_READ_OUTPUT_CHARS && linesOutput > 0) {
                capped = true;
                break;
            }
            out.append(processed).append('\n');
            total += added;
            linesOutput++;
        }

        int startLine = offset + 1;
        int endLine = offset + linesOutput;
        out.append('\n');
        if (capped) {
            out.append("[已显示第 ").append(startLine).append('-').append(endLine)
                    .append(" 行，输出达单次上限，传 offset=").append(endLine)
                    .append(" 查看后续]");
        } else if (linesOutput >= limit) {
            out.append("[已显示第 ").append(startLine).append('-').append(endLine)
                    .append(" 行，可能还有更多，传 offset=").append(offset + limit)
                    .append(" 查看后续]");
        } else {
            out.append("[已显示到文件末尾，第 ").append(startLine).append('-').append(endLine).append(" 行]");
        }
        return out.toString();
    }

    /**
     * 读取字符串参数。
     */
    private String readString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 读取整数参数，非法时返回默认值。
     */
    private int readInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
