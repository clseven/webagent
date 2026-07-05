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
  - src/main/resources/static/js/components/FilePreviewer.js
  - src/test/js/file-previewer-office.test.js
updated: 2026-07-06
---

# Office 文件预览

## 职责

Office 文件预览把 doc/docx/xls/xlsx/ppt/pptx 等格式转换为 PDF，再交给前端统一预览。它服务两类入口：

- 工作区文件预览：来自 `/home/gem` 的文件。
- 知识库文件预览：来自 RAG 文档原文。

## 后端流程

- `SandboxController.previewFile` 接收 sessionId 和 sandbox path。
- 文本、图片、PDF 可直接返回。
- Office 文件走 `OfficePreviewService`，通常转换成 PDF 后返回 bytes。
- 上传 Office 文件后，`OfficePreviewAsyncService` 可提前触发转换，降低首次打开等待。

## 前端流程

`FilePreviewer.js` 统一处理：

- 文本/代码/Markdown/CSV：UTF-8 解码并渲染。
- 图片：Blob URL + lightbox + 缩放。
- PDF：iframe 预览。
- Office：预览类型映射为 PDF，转换时显示“正在转换文档”。
- 知识库文件：可同时加载原文和切片列表，支持切片搜索、高亮和复制。
- 工作区文件：通过 `api.previewFileInSandbox` 读取 bytes，通过 `api.downloadFileFromSandbox` 下载。

## 当前前端增强

- 图片预览默认 lightbox，支持同一助手消息内图库前后切换。
- 图片缩放初始值约 60%，可放大、缩小、重置。
- 文件头部有下载入口。
- 对 `local-image` 来源不会错误释放外部传入的 Blob URL。
- `file-previewer-office.test.js` 覆盖 Office 扩展名映射和截图产物过滤逻辑。

## 相关页面

[[文件与工作区模块]] · [[文件上传与预览]] · [[前端模块]]