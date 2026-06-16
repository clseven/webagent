package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文件内容搜索工具 — 在文件内容中搜索正则匹配（AIO 沙箱）
 *
 * <h3>用途</h3>
 * <p>让 LLM 能在文件中查找特定内容，比用 grep 命令更高效：</p>
 * <ul>
 *   <li>查找代码中的函数定义（搜索 "def "、"class "）</li>
 *   <li>定位错误日志中的关键字</li>
 *   <li>提取特定模式的数据（邮箱、URL 等）</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>file — 要搜索的文件路径</li>
 *   <li>regex — 正则表达式（支持完整正则语法）</li>
 * </ul>
 *
 * <h3>返回值</h3>
 * <p>返回匹配的行内容和行号列表，例如：</p>
 * <pre>
 * 找到 3 个匹配：
 * 行 42: def main():
 * 行 78: class UserService:
 * 行 102: def parse_args():
 * </pre>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class FileSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileSearchTool.class);
    private static final String NAME = "file_search";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "file", Map.of(
                        "type", "string",
                        "description", "文件路径"
                ),
                "regex", Map.of(
                        "type", "string",
                        "description", "正则表达式（支持完整正则语法）"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("file", "regex")
        );

        return new ToolDefinition(
                NAME,
                "在文件内容中搜索正则匹配。返回匹配的行内容和行号。比用 grep 命令更高效。",
                parameters,
                "AIO"
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String sessionId, Map<String, Object> arguments) {
        String file = (String) arguments.get("file");
        String regex = (String) arguments.get("regex");

        if (file == null || file.isBlank()) return "错误：file 不能为空";
        if (regex == null || regex.isBlank()) return "错误：regex 不能为空";

        try {
            var client = factory.getAioClient(sessionId);
            Map<String, Object> result = client.files().search(file, regex);

            if (result == null) {
                return "错误：搜索失败，无响应";
            }

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                List<?> matches = data != null ? (List<?>) data.get("matches") : List.of();
                List<?> lineNumbers = data != null ? (List<?>) data.get("line_numbers") : List.of();
                log.info("文件搜索完成: {} pattern={} 匹配 {} 行", file, regex, matches.size());

                if (matches.isEmpty()) {
                    return "未找到匹配项";
                }

                StringBuilder sb = new StringBuilder();
                sb.append("找到 ").append(matches.size()).append(" 个匹配：\n");
                for (int i = 0; i < matches.size(); i++) {
                    String lineNum = i < lineNumbers.size() ? String.valueOf(lineNumbers.get(i)) : "?";
                    sb.append("行 ").append(lineNum).append(": ").append(matches.get(i)).append("\n");
                }
                return sb.toString().trim();
            } else {
                return "错误：搜索失败 - " + result.get("message");
            }
        } catch (Exception e) {
            log.error("文件搜索失败: {}", file, e);
            return "错误：搜索失败 - " + e.getMessage();
        }
    }
}
