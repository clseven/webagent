# 前端文件预览功能设计

> 状态：**已实现**
> 最后更新：2026-06-09

## 当前实现摘要

用户文件以本地文件系统为持久化主副本，AIO 沙箱是可重建副本：

```text
本地知识库：uploads/users/{userId}/knowledge/{kbId}/{fileName}
本地普通上传：uploads/users/{userId}/uploads/{fileName}

沙箱知识库：/home/gem/knowledge/{kbId}/{fileName}
沙箱普通上传：/home/gem/uploads/{fileName}
```

知识库预览和普通文件预览复用 `FilePreviewer`，但数据链路不同：

- 知识库预览为双栏。左侧请求 `/api/rag/document/{docId}/file`，
  右侧独立请求 `/api/rag/document/{docId}/chunks`。
- 普通文件预览为单栏，只请求
  `/api/sessions/{sessionId}/files/preview?path=...`，不加载知识库切片。
- `fileType` 保留原始扩展名，用于标题和图标；`previewType` 决定渲染器。
  例如 DOCX 转换成功后为 `fileType: docx`、`previewType: pdf`。

Office 文件在沙箱内按需使用 LibreOffice 转换为 PDF。支持
DOC/DOCX/ODT/RTF、XLS/XLSX/ODS、PPT/PPTX/ODP。每次转换使用独立
LibreOffice profile，并按源文件哈希缓存：

```text
/home/gem/knowledge/{kbId}/.preview/{documentId}-{sourceHash}.pdf
/home/gem/temp/previews/{pathHash}-{sourceHash}.pdf
```

下载接口仍返回原始文件，转换后的 PDF 仅用于在线预览。

---

## 一、需求背景

系统有两个场景需要文件预览：

| 场景 | 位置 | 文件来源 | 典型文件类型 |
|------|------|----------|--------------|
| **知识库管理** | 知识库页面 | 用户上传的文档 | PDF、Word、Excel、PPT、TXT、Markdown、图片 |
| **工作空间** | Agent 会话页 | 沙箱内的文件（上传或生成） | 代码、日志、配置文件、输出图片、生成的文档 |

**目标**：提供统一的文件预览能力，覆盖常用格式，用户体验一致。

---

## 二、支持的文件格式

### 2.1 格式分类与处理方式

| 格式 | 浏览器原生支持 | 预览方案 | 备注 |
|------|----------------|----------|------|
| **PDF** | ✅ Chrome/Edge/Firefox 内置 | iframe 直接渲染 | 零成本，效果最好 |
| **图片** (jpg/png/gif/webp/svg) | ✅ | `<img>` 或 iframe | 零成本 |
| **纯文本** (txt/log/json/xml) | ✅ | `<pre>` 渲染 | 需要语法高亮可选 |
| **Markdown** (.md) | ❌ | 前端 markdown 渲染器 | 如 marked.js + highlight.js |
| **代码** (js/ts/py/java/go 等) | ❌ | Monaco Editor 或 CodeMirror | 只读模式 |
| **Word** (docx/doc) | ❌ | 后端转 PDF → 前端 PDF viewer | 需要 LibreOffice |
| **Excel** (xlsx/xls/csv) | ❌ | 后端转 PDF 或 前端 sheetjs | CSV 可当文本处理 |
| **PPT** (pptx/ppt) | ❌ | 后端转 PDF → 前端 PDF viewer | 需要 LibreOffice |
| **视频/音频** (mp4/mp3/webm) | ✅ | `<video>` / `<audio>` | 非文档，但可能需要 |

### 2.1 一期支持范围（建议）

**必须支持**：
- PDF（直接渲染）
- 图片（jpg/png/gif/webp/svg）
- 纯文本（txt/log/json/xml）
- Markdown（.md）
- 代码文件（常见语言）

**二期支持**：
- Word（docx）→ 转 PDF
- Excel（xlsx/csv）→ 转 PDF 或前端表格
- PPT（pptx）→ 转 PDF

**暂不支持**：
- doc（旧版 Word）、xls（旧版 Excel）、ppt（旧版 PPT）—— 转换效果差，建议提示用户转换后上传

---

## 三、技术方案

### 3.1 方案总览

```
┌─────────────────────────────────────────────────────────────┐
│                      前端预览组件                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│  │ PDF     │  │ 图片    │  │ 文本/代码│  │ Markdown│        │
│  │ Viewer  │  │ Viewer  │  │ Viewer  │  │ Viewer  │        │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘        │
└───────┼────────────┼────────────┼────────────┼──────────────┘
        │            │            │            │
        └────────────┴────────────┴────────────┘
                           │
                    统一预览 API
                           │
        ┌──────────────────┴──────────────────┐
        │                                     │
┌───────▼───────┐                    ┌────────▼────────┐
│ 知识库文件     │                    │ 沙箱文件         │
│ /api/rag/...  │                    │ /api/sessions/..│
└───────────────┘                    └─────────────────┘
```

### 3.2 后端统一预览接口

**知识库文件预览**：

```
GET /api/rag/documents/{docId}/preview
GET /api/rag/documents/{docId}/download

Response:
  - Content-Type: 根据文件类型动态设置
  - Content-Disposition: inline（预览）或 attachment（下载）
  - Body: 文件二进制流
```

**沙箱文件预览**（改造现有接口）：

```
GET /api/sessions/{sessionId}/files/preview?path=/home/gem/workspace/xxx.pdf
GET /api/sessions/{sessionId}/files/download?path=/home/gem/workspace/xxx.pdf

Response:
  - Content-Type: 根据文件类型动态设置
  - Content-Disposition: inline（预览）或 attachment（下载）
  - Body: 文件二进制流
```

### 3.3 Content-Type 映射

后端根据文件扩展名设置正确的 `Content-Type`：

| 扩展名 | Content-Type |
|--------|--------------|
| .pdf | application/pdf |
| .jpg / .jpeg | image/jpeg |
| .png | image/png |
| .gif | image/gif |
| .webp | image/webp |
| .svg | image/svg+xml |
| .txt / .log | text/plain |
| .json | application/json |
| .xml | application/xml |
| .md | text/markdown |
| .html | text/html |
| .mp4 | video/mp4 |
| .mp3 | audio/mpeg |
| .docx | application/vnd.openxmlformats-officedocument.wordprocessingml.document |
| .xlsx | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet |
| .pptx | application/vnd.openxmlformats-officedocument.presentationml.presentation |
| 其他 | application/octet-stream |

### 3.4 Office 文档转换（二期）

**后端转换服务**：

```java
// 新增 DocumentConversionService
public interface DocumentConversionService {
    /**
     * 将 Office 文档转换为 PDF
     * @param inputStream 原文件流
     * @param filename 文件名（用于判断类型）
     * @return PDF 文件流
     */
    InputStream convertToPdf(InputStream inputStream, String filename);

    /**
     * 检查是否需要转换
     */
    boolean needsConversion(String filename);
}
```

**实现方式**：
- 依赖：LibreOffice（`apt install libreoffice`）
- 调用：`libreoffice --headless --convert-to pdf --outdir /tmp input.docx`
- 缓存：转换后的 PDF 缓存到 `{原文件目录}/preview/` 目录，避免重复转换

**转换时机**：
- 方案 A：上传时转换（推荐，预览时零延迟）
- 方案 B：首次预览时转换（懒加载，节省存储）

---

## 四、前端组件设计

### 4.1 统一预览组件 FilePreview

**Props**：

```typescript
interface FilePreviewProps {
  url: string;           // 文件预览 URL
  filename: string;      // 文件名（用于判断类型和显示标题）
  visible: boolean;      // 是否显示
  onClose: () => void;   // 关闭回调
  width?: string;        // 弹层宽度，默认 '80%'
  height?: string;       // 弹层高度，默认 '80vh'
}
```

**使用示例**：

```jsx
<FilePreview
  url="/api/rag/documents/123/preview"
  filename="用户手册.pdf"
  visible={previewVisible}
  onClose={() => setPreviewVisible(false)}
/>
```

### 4.2 组件内部结构

```jsx
function FilePreview({ url, filename, visible, onClose, width, height }) {
  const fileType = getFileType(filename);

  const renderContent = () => {
    switch (fileType) {
      case 'pdf':
        return <iframe src={url} width="100%" height="100%" />;
      case 'image':
        return <img src={url} alt={filename} style={{ maxWidth: '100%' }} />;
      case 'text':
      case 'code':
        return <TextPreview url={url} language={getLanguage(filename)} />;
      case 'markdown':
        return <MarkdownPreview url={url} />;
      case 'video':
        return <video src={url} controls style={{ maxWidth: '100%' }} />;
      case 'audio':
        return <audio src={url} controls />;
      case 'office':
        // 二期：显示转换后的 PDF
        return <iframe src={`${url}?format=pdf`} width="100%" height="100%" />;
      default:
        return <UnsupportedFile filename={filename} onDownload={() => ...} />;
    }
  };

  return (
    <Drawer open={visible} onClose={onClose} width={width}>
      <Drawer.Header>
        <span>{filename}</span>
        <Button onClick={handleDownload}>下载</Button>
      </Drawer.Header>
      <Drawer.Body style={{ height }}>
        {renderContent()}
      </Drawer.Body>
    </Drawer>
  );
}
```

### 4.3 文件类型判断

```javascript
function getFileType(filename) {
  const ext = filename.split('.').pop()?.toLowerCase();

  const typeMap = {
    // PDF
    pdf: 'pdf',
    // 图片
    jpg: 'image', jpeg: 'image', png: 'image', gif: 'image',
    webp: 'image', svg: 'image', bmp: 'image', ico: 'image',
    // 文本
    txt: 'text', log: 'text', json: 'text', xml: 'text',
    yaml: 'text', yml: 'text', ini: 'text', conf: 'text',
    csv: 'text', tsv: 'text',
    // Markdown
    md: 'markdown', markdown: 'markdown',
    // 代码
    js: 'code', ts: 'code', jsx: 'code', tsx: 'code',
    py: 'code', java: 'code', go: 'code', rs: 'code',
    c: 'code', cpp: 'code', h: 'code', hpp: 'code',
    cs: 'code', rb: 'code', php: 'code', swift: 'code',
    kt: 'code', scala: 'code', sh: 'code', bash: 'code',
    sql: 'code', html: 'code', css: 'code', scss: 'code',
    // Office
    docx: 'office', doc: 'office',
    xlsx: 'office', xls: 'office',
    pptx: 'office', ppt: 'office',
    // 视频
    mp4: 'video', webm: 'video', mov: 'video', avi: 'video',
    // 音频
    mp3: 'audio', wav: 'audio', ogg: 'audio', flac: 'audio',
  };

  return typeMap[ext] || 'unknown';
}
```

### 4.4 文本/代码预览子组件

```jsx
function TextPreview({ url, language }) {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(url)
      .then(res => res.text())
      .then(text => {
        setContent(text);
        setLoading(false);
      });
  }, [url]);

  if (loading) return <Spin />;

  // 纯文本：直接显示
  if (language === 'text') {
    return (
      <pre style={{
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        fontFamily: 'monospace',
        fontSize: '13px',
        padding: '16px',
        background: '#f5f5f5',
        borderRadius: '4px',
        maxHeight: '100%',
        overflow: 'auto'
      }}>
        {content}
      </pre>
    );
  }

  // 代码：使用 Monaco Editor 只读模式
  return (
    <MonacoEditor
      value={content}
      language={language}
      options={{
        readOnly: true,
        minimap: { enabled: false },
        lineNumbers: 'on',
        wordWrap: 'on',
        scrollBeyondLastLine: false,
      }}
    />
  );
}
```

### 4.5 Markdown 预览子组件

```jsx
import { marked } from 'marked';
import hljs from 'highlight.js';
import 'highlight.js/styles/github.css';

function MarkdownPreview({ url }) {
  const [html, setHtml] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(url)
      .then(res => res.text())
      .then(text => {
        marked.setOptions({
          highlight: (code, lang) => {
            if (lang && hljs.getLanguage(lang)) {
              return hljs.highlight(code, { language: lang }).value;
            }
            return hljs.highlightAuto(code).value;
          },
          breaks: true,
          gfm: true,
        });
        setHtml(marked(text));
        setLoading(false);
      });
  }, [url]);

  if (loading) return <Spin />;

  return (
    <div
      className="markdown-body"
      dangerouslySetInnerHTML={{ __html: html }}
      style={{
        padding: '16px',
        maxHeight: '100%',
        overflow: 'auto'
      }}
    />
  );
}
```

---

## 五、知识库文件预览集成

### 5.1 后端新增接口

在 `RagController` 新增：

```java
/**
 * 预览知识库文档
 */
@GetMapping("/documents/{docId}/preview")
public void previewDocument(@PathVariable Long docId, HttpServletResponse response) {
    KnowledgeDocumentEntity document = knowledgeService.getDocument(docId);

    // 权限检查：确保用户有权限访问该文档
    Long userId = UserContext.getCurrentUserId();
    if (!userId.equals(document.getUserId())) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return;
    }

    Path filePath = Paths.get(document.getStoragePath());
    if (!Files.exists(filePath)) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
    }

    String filename = document.getFileName();
    String contentType = getContentType(filename);

    response.setContentType(contentType);
    response.setHeader("Content-Disposition", "inline; filename=\"" + encodeFilename(filename) + "\"");
    response.setContentLengthLong(Files.size(filePath));

    Files.copy(filePath, response.getOutputStream());
}

/**
 * 下载知识库文档
 */
@GetMapping("/documents/{docId}/download")
public void downloadDocument(@PathVariable Long docId, HttpServletResponse response) {
    // 同上，但 Content-Disposition 为 attachment
    response.setHeader("Content-Disposition", "attachment; filename=\"" + encodeFilename(filename) + "\"");
}
```

### 5.2 前端知识库页面改造

```
┌──────────────────────────────────────────────────────────────────┐
│ 知识库：产品手册                                                   │
├──────────────────────────────────────────────────────────────────┤
│ ☑ 用户手册 v1.2.pdf    12 切片  [👁 预览] [⬇ 下载] [···]         │
│ ☑ API 文档.pdf         45 切片  [👁 预览] [⬇ 下载] [···]         │
│ ☐ 内部草稿.docx         8 切片   [👁 预览] [⬇ 下载] [···]         │
│ ☑ README.md            3 切片   [👁 预览] [⬇ 下载] [···]         │
└──────────────────────────────────────────────────────────────────┘
```

**点击预览**：
1. 调用 `GET /api/rag/documents/{docId}/preview`
2. 弹出 `FilePreview` 组件
3. 根据文件类型渲染对应预览器

---

## 六、工作空间文件预览集成

### 6.1 后端接口改造

改造现有 `SandboxController` 的下载接口：

```java
/**
 * 预览沙箱文件
 */
@GetMapping("/{sessionId}/files/preview")
public void previewFile(
        @PathVariable String sessionId,
        @RequestParam String path,
        HttpServletResponse response) {
    try {
        agentService.getSession(sessionId);
        AioSandboxClient client = sandboxClientFactory.getAioClient(sessionId);
        byte[] fileContent = client.downloadFile(path);

        String filename = path.substring(path.lastIndexOf('/') + 1);
        String contentType = getContentType(filename);

        response.setContentType(contentType);
        response.setHeader("Content-Disposition", "inline; filename=\"" + encodeFilename(filename) + "\"");
        response.setContentLength(fileContent.length);
        response.getOutputStream().write(fileContent);
    } catch (Exception e) {
        response.setStatus(500);
    }
}

/**
 * 下载沙箱文件
 */
@GetMapping("/{sessionId}/files/download")
public void downloadFile(
        @PathVariable String sessionId,
        @RequestParam String path,
        HttpServletResponse response) {
    // 同上，但 Content-Disposition 为 attachment
}
```

### 6.2 前端工作空间页面

工作空间文件列表（假设已存在）：

```
┌──────────────────────────────────────────────────────────────────┐
│ 工作空间 - Session: abc-123-def                                   │
├──────────────────────────────────────────────────────────────────┤
│ 📁 /home/gem/workspace/                                           │
│   📁 output/                                                      │
│     📄 report.pdf       [👁 预览] [⬇ 下载]                        │
│     📄 data.json       [👁 预览] [⬇ 下载]                        │
│     🖼 screenshot.png   [👁 预览] [⬇ 下载]                        │
│   📁 uploads/                                                     │
│     📄 config.yaml      [👁 预览] [⬇ 下载]                        │
│   📄 main.py            [👁 预览] [⬇ 下载]                        │
└──────────────────────────────────────────────────────────────────┘
```

**与知识库共享同一个 `FilePreview` 组件**，只是 URL 不同：

```jsx
// 知识库预览
<FilePreview url="/api/rag/documents/123/preview" filename="xxx.pdf" ... />

// 工作空间预览
<FilePreview url="/api/sessions/abc-123-def/files/preview?path=/home/gem/workspace/output/report.pdf" filename="report.pdf" ... />
```

---

## 七、Office 文档转换方案（二期）

### 7.1 后端实现

**依赖安装**：

```bash
# Ubuntu/Debian
apt update && apt install -y libreoffice

# 验证安装
libreoffice --version
```

**转换服务**：

```java
@Service
public class DocumentConversionServiceImpl implements DocumentConversionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentConversionServiceImpl.class);

    @Value("${rag.conversion.timeout:30}")
    private int conversionTimeout; // 秒

    @Override
    public InputStream convertToPdf(InputStream inputStream, String filename) {
        try {
            // 1. 保存临时文件
            Path tempDir = Files.createTempDirectory("convert_");
            Path inputFile = tempDir.resolve(filename);
            Files.copy(inputStream, inputFile);

            // 2. 调用 LibreOffice 转换
            ProcessBuilder pb = new ProcessBuilder(
                "libreoffice", "--headless", "--convert-to", "pdf",
                "--outdir", tempDir.toString(),
                inputFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(conversionTimeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("转换超时");
            }

            // 3. 读取转换后的 PDF
            String pdfName = filename.replaceAll("\\.[^.]+$", ".pdf");
            Path pdfFile = tempDir.resolve(pdfName);
            if (!Files.exists(pdfFile)) {
                throw new RuntimeException("转换失败：PDF 未生成");
            }

            // 4. 返回流，临时文件在 JVM 退出时清理
            return Files.newInputStream(pdfFile);

        } catch (Exception e) {
            log.error("文档转换失败: {}", filename, e);
            throw new RuntimeException("文档转换失败: " + e.getMessage());
        }
    }

    @Override
    public boolean needsConversion(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return Set.of("docx", "doc", "xlsx", "xls", "pptx", "ppt").contains(ext);
    }
}
```

### 7.2 缓存策略

转换后的 PDF 缓存到原文件同目录：

```
./uploads/knowledge/{userId}/
  ├── doc_1.pdf              ← 原文件
  ├── doc_2.docx             ← 原文件
  └── preview/
      └── doc_2.pdf          ← 转换后的 PDF 缓存
```

**缓存逻辑**：
1. 预览时检查 `preview/doc_x.pdf` 是否存在
2. 存在 → 直接返回
3. 不存在 → 转换 → 缓存 → 返回
4. 原文件更新/删除时 → 清理对应缓存

---

## 八、前端 UI 细节

### 8.1 预览弹层样式

```
┌─────────────────────────────────────────────────────────────────┐
│ 📄 用户手册 v1.2.pdf                                    [⬇] [✕] │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                                                                 │
│                    PDF / 图片 / 代码 内容                        │
│                                                                 │
│                                                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

- 宽度：80%（可拖拽调整）
- 高度：80vh
- 顶部：文件名 + 下载按钮 + 关闭按钮
- 内容区：根据文件类型渲染
- 背景：半透明遮罩

### 8.2 不支持预览的文件

```
┌─────────────────────────────────────────────────────────────────┐
│ 📄 data.bin                                             [⬇] [✕] │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                      ⚠️                                         │
│              该文件类型暂不支持预览                               │
│                                                                 │
│                    [下载文件]                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 九、配置项

### 9.1 application.yml

```yaml
rag:
  # 现有配置...
  preview:
    max-size: 52428800       # 预览文件最大 50MB，超过则提示下载
    conversion:
      enabled: true          # 是否启用 Office 文档转换
      timeout: 30            # 转换超时时间（秒）
      cache-dir: ./uploads/knowledge/preview  # 转换缓存目录
```

---

## 十、文件变更清单

### 新增文件

| 路径 | 说明 |
|------|------|
| `service/DocumentConversionService.java` | Office 文档转换接口 |
| `service/impl/DocumentConversionServiceImpl.java` | LibreOffice 实现 |
| `util/ContentTypeUtil.java` | Content-Type 映射工具类 |

### 修改文件

| 路径 | 改动 |
|------|------|
| `controller/RagController.java` | 新增 `previewDocument`、`downloadDocument` 接口 |
| `controller/SandboxController.java` | 改造 `aio/download` 为统一的预览/下载接口 |
| `service/KnowledgeService.java` | 新增 `getDocumentStream(docId)` 方法 |
| `resources/application.yml` | 新增 `rag.preview.*` 配置 |

### 前端新增

| 路径 | 说明 |
|------|------|
| `components/FilePreview/index.jsx` | 统一文件预览组件 |
| `components/FilePreview/TextPreview.jsx` | 文本/代码预览子组件 |
| `components/FilePreview/MarkdownPreview.jsx` | Markdown 预览子组件 |
| `utils/fileType.js` | 文件类型判断工具函数 |

---

## 十一、实施步骤

### 一期（必须）

1. [ ] 后端新增统一预览接口（知识库 + 沙箱）
2. [ ] 后端 `ContentType` 动态映射
3. [ ] 前端 `FilePreview` 组件（PDF / 图片 / 文本 / 代码 / Markdown）
4. [ ] 知识库页面集成预览按钮
5. [ ] 工作空间页面集成预览按钮

### 二期（可选）

6. [ ] 后端 Office 文档转换服务（LibreOffice）
7. [ ] 转换缓存机制
8. [ ] 前端支持 Office 预览

---

## 十二、待定项

- [ ] 代码预览用 Monaco Editor 还是 CodeMirror？（建议 Monaco，VSCode 同款）
- [ ] Office 文档转换时机：上传时 vs 首次预览时？（建议首次预览时懒加载）
- [ ] 预览文件大小限制：多少 MB？（建议 50MB）
- [ ] 是否支持视频/音频预览？（建议一期支持，原生 `<video>`/`<audio>` 零成本）
