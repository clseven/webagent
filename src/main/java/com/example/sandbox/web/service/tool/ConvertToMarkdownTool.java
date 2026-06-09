package com.example.sandbox.web.service.tool;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * URI 转 Markdown 工具 — 把网页或文件转换成 Markdown（AIO 沙箱）
 *
 * <h3>用途</h3>
 * <p>让 LLM 能读取网页或文档的纯净内容：</p>
 * <ul>
 *   <li>分析网页文章（去除广告、导航等噪音）</li>
 *   <li>提取 PDF/Word 文档正文</li>
 *   <li>把 HTML 转成 LLM 更好理解的 Markdown</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>uri — 要转换的 URI</li>
 * </ul>
 *
 * <h3>支持的 URI 格式</h3>
 * <ul>
 *   <li>网页 URL（如 https://example.com/article）</li>
 *   <li>本地文件路径（如 /home/gem/uploads/document.pdf）</li>
 * </ul>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class ConvertToMarkdownTool implements Tool {

    private static final String NAME = "convert_to_markdown";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("uri", Map.of(
                "type", "string",
                "description", "要转换的 URI，支持网页 URL（如 https://example.com）或文件路径"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("uri")
        );

        return new ToolDefinition(
                NAME,
                "将 URI（网页或文件）转换为 Markdown 格式。适用于：分析网页内容、提取文章正文、转换文档为可读格式。",
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String uri = (String) arguments.get("uri");
        if (uri == null || uri.isBlank()) {
            return "错误：URI 不能为空";
        }

        try {
            AioSandboxClient client = factory.getAioClient(sessionId);
            String markdown = client.convertToMarkdown(uri);
            return markdown;
        } catch (Exception e) {
            return "转换失败：" + e.getMessage();
        }
    }
}
