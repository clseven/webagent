---
project: webagent-clean
type: module
status: verified
area:
  - files
  - preview
  - frontend
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/OfficePreviewService.java
  - src/main/java/com/example/sandbox/web/service/impl/OfficePreviewAsyncService.java
  - src/main/java/com/example/sandbox/web/controller/SandboxController.java
  - src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java
  - src/main/resources/static/js/components/FilePreviewer.js
  - src/test/js/file-previewer-office.test.js
updated: 2026-07-09
---

# Office 文件预览

## 1. 模块概览

Office 文件预览把 doc/docx/xls/xlsx/ppt/pptx 等格式转换为 PDF，再交给前端统一预览。

当前实现里需要特别注意四点：

- 预览服务两类入口：工作区文件（来自 `/home/gem`）和知识库文件（来自 RAG 文档原文）。
- Office 转换在沙箱内完成，使用 LibreOffice headless 模式。
- 上传 Office 文件后可提前异步触发转换（`rag.preview.conversion.enabled=true`），降低首次打开等待。
- 转换超时由 `rag.preview.conversion.timeout-seconds` 控制，默认 120 秒。

## 2. 后端流程

### 2.1 工作区文件预览

入口方法：`SandboxController.previewFile`

1. 接收 sessionId 和 sandbox path。
2. 文本、图片、PDF 可直接按 mediaType 返回 bytes。
3. Office 文件走 `OfficePreviewService`，在沙箱内用 LibreOffice 转换为 PDF 后返回 bytes。
4. 转换超时 120 秒，超时返回错误。

### 2.2 知识库文件预览

入口方法：`KnowledgeServiceImpl.getPreviewContent`（`GET /api/rag/document/{docId}/file`）

1. 调用 `migrationService.ensureCanonicalFile(document)` 把历史旧路径文件规范化到当前用户级路径。
2. 调用 `ensureSandboxFile` 按需恢复：沙箱缺文件时从本地补传（`mkdir -p` + `writeBytes`）。
3. 从沙箱读取原始文件返回预览。
4. 可转换的 Office 文件走转换链路，其余按 mediaType 直接返回字节。

关键差异：`getPreviewContent` 会按需补传沙箱文件，`getFileContent` 不会（沙箱文件缺失即报错）。

### 2.3 异步预转换

入口方法：`OfficePreviewAsyncService`

1. 知识库文档沙箱同步成功后，如果 `rag.preview.conversion.enabled=true`，异步触发 Office 预转换。
2. 预转换在沙箱内完成，结果缓存到沙箱文件系统。
3. 首次打开预览时直接读取已转换的 PDF，无需等待。
4. 预转换失败不影响文档状态（仍为 `READY`）。
5. Milvus 关闭时跳过此步骤（6.2 step 11 跳过）。

## 3. 前端流程

`FilePreviewer.js` 统一处理所有预览类型：

| 文件类型 | 预览方式 |
| --- | --- |
| 文本/代码/Markdown/CSV | UTF-8 解码并渲染 |
| 图片 | Blob URL + lightbox + 缩放 |
| PDF | iframe 预览 |
| Office | 预览类型映射为 PDF，转换时显示"正在转换文档" |
| 知识库文件 | 可同时加载原文和切片列表，支持切片搜索、高亮和复制 |
| 工作区文件 | 通过 `api.previewFileInSandbox` 读取 bytes，通过 `api.downloadFileFromSandbox` 下载 |

## 4. 前端增强

- 图片预览默认 lightbox，支持同一助手消息内图库前后切换。
- 图片缩放初始值约 60%，可放大、缩小、重置。
- 文件头部有下载入口。
- 对 `local-image` 来源不会错误释放外部传入的 Blob URL。
- `file-previewer-office.test.js` 覆盖 Office 扩展名映射和截图产物过滤逻辑。

## 5. 配置

| 配置项 | 当前值 | 环境变量 | 说明 |
| --- | --- | --- | --- |
| `rag.preview.conversion.enabled` | `true` | `RAG_PREVIEW_CONVERSION_ENABLED` | 沙箱同步成功后是否异步触发 Office 预转换 |
| `rag.preview.conversion.timeout-seconds` | `120` | `RAG_PREVIEW_CONVERSION_TIMEOUT_SECONDS` | 单次 Office 预转换超时 |

## 6. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| Office 预览转换超时 | LibreOffice 是否可用、文件大小 | 超时 120 秒，大文件可能超时 |
| 知识库文件预览不可用 | 本地 `storagePath`、`sandboxSynced`、`ensureSandboxFile` | `/file` 接口会按需从本地补传 |
| 预转换未触发 | `rag.preview.conversion.enabled`、沙箱同步是否成功 | Milvus 关闭时跳过 |
| 转换失败但文档 READY | `OfficePreviewAsyncService` 日志 | 预转换失败不影响文档状态 |
| 历史文件预览失败 | `ensureCanonicalFile` 迁移 | 旧路径文件需要先迁移 |

## 7. 相关页面

[[文件与工作区模块]] · [[文件上传与预览]] · [[前端模块]] · [[文档摄取与切片]]