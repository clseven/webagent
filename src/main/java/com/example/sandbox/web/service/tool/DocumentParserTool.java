package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档解析工具 — 提取二进制文档的文本内容
 *
 * <h3>支持的格式</h3>
 * <table>
 *   <tr><th>类型</th><th>扩展名</th><th>使用工具</th></tr>
 *   <tr><td>PDF</td><td>pdf</td><td>pdftotext (poppler-utils)</td></tr>
 *   <tr><td>Word</td><td>docx</td><td>python-docx / pandoc</td></tr>
 *   <tr><td>Excel</td><td>xlsx/xls</td><td>pandas + openpyxl</td></tr>
 *   <tr><td>CSV</td><td>csv</td><td>pandas</td></tr>
 * </table>
 *
 * <h3>使用场景</h3>
 * <p>用户上传了 PDF/Word/Excel 文档时，LLM 调用此工具提取文本内容，
 * 后续基于提取的内容进行分析、总结、问答。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>path — 文档路径（支持 pdf/docx/xlsx/xls/csv）</li>
 * </ul>
 *
 * <h3>依赖要求</h3>
 * <p>沙箱需要预装相关工具（pdftotext、python-docx、pandas 等）。</p>
 *
 * @author example
 * @date 2026/05/29
 */
@Component
public class DocumentParserTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserTool.class);
    private static final String NAME = "parse_document";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "要解析的文档路径（支持 pdf/docx/xlsx/xls/csv）"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path")
        );

        return new ToolDefinition(
                NAME,
                "解析文档内容（PDF/Word/Excel），提取文本信息",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }

        // 根据文件扩展名选择解析方式
        String ext = getExtension(path).toLowerCase();

        try {
            SandboxClient client = factory.getClient(sessionId);

            return switch (ext) {
                case "pdf" -> parsePdf(client, path);
                case "docx" -> parseWord(client, path);
                case "xlsx", "xls" -> parseExcel(client, path);
                case "csv" -> parseCsv(client, path);
                default -> "不支持的文件类型: " + ext + "\n支持的类型: pdf, docx, xlsx, xls, csv";
            };
        } catch (Exception e) {
            log.error("文档解析失败: {} - {}", path, e.getMessage());
            return "解析失败：" + e.getMessage();
        }
    }

    /**
     * 解析 PDF 文件（使用 pdftotext）
     */
    private String parsePdf(SandboxClient client, String path) {
        // pdftotext 输出到 stdout (-)
        String command = "pdftotext \"" + path + "\" - 2>/dev/null || echo '[PDF解析失败，请确认已安装 poppler-utils]'";
        String result = client.execCommand(command);

        if (result == null || result.isBlank()) {
            return "PDF 内容为空或解析失败";
        }
        return "## PDF 内容: " + path + "\n\n" + result;
    }

    /**
     * 解析 Word 文件（使用 python-docx）
     */
    private String parseWord(SandboxClient client, String path) {
        String script = """
                import sys
                try:
                    from docx import Document
                    doc = Document(sys.argv[1])
                    for para in doc.paragraphs:
                        print(para.text)
                except Exception as e:
                    print(f'[Word解析错误: {e}]', file=sys.stderr)
                """;

        String command = "python3 -c '" + script.replace("\n", "; ") + "' \"" + path + "\" 2>/dev/null";
        String result = client.execCommand(command);

        if (result == null || result.contains("解析错误")) {
            // 尝试备用方案：pandoc
            command = "pandoc \"" + path + "\" -t plain 2>/dev/null || echo '[Word解析失败，请确认已安装 python-docx 或 pandoc]'";
            result = client.execCommand(command);
        }

        return "## Word 内容: " + path + "\n\n" + (result == null ? "解析失败" : result);
    }

    /**
     * 解析 Excel 文件（使用 Python）
     */
    private String parseExcel(SandboxClient client, String path) {
        String script = """
                import sys, json
                try:
                    import pandas as pd
                    df = pd.read_excel(sys.argv[1], sheet_name=None)
                    for name, sheet in df.items():
                        print(f'### Sheet: {name}')
                        print(sheet.to_string())
                        print()
                except Exception as e:
                    print(f'[Excel解析错误: {e}]', file=sys.stderr)
                """;

        String command = "python3 -c '" + script.replace("\n", "; ") + "' \"" + path + "\" 2>/dev/null";
        String result = client.execCommand(command);

        if (result == null || result.contains("解析错误")) {
            return "Excel 解析失败，请确认沙盒已安装 pandas 和 openpyxl\n" +
                   "安装命令: pip install pandas openpyxl";
        }

        return "## Excel 内容: " + path + "\n\n" + result;
    }

    /**
     * 解析 CSV 文件
     */
    private String parseCsv(SandboxClient client, String path) {
        // CSV 可以直接读取，或用 Python 格式化输出
        String script = """
                import sys
                try:
                    import pandas as pd
                    df = pd.read_csv(sys.argv[1])
                    print(df.to_string())
                except:
                    # 备用：直接输出
                    with open(sys.argv[1], 'r') as f:
                        print(f.read())
                """;

        String command = "python3 -c '" + script.replace("\n", "; ") + "' \"" + path + "\" 2>/dev/null";
        String result = client.execCommand(command);

        return "## CSV 内容: " + path + "\n\n" + (result == null ? "解析失败" : result);
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0 && lastDot < path.length() - 1) {
            return path.substring(lastDot + 1);
        }
        return "";
    }
}
