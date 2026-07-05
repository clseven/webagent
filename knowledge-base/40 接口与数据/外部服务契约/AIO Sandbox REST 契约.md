---
project: webagent-clean
type: contract
status: verified
area:
  - sandbox
  - external-contract
tags:
  - project/webagent-clean
source:
  - docs/sandbox-api/README.md
  - docs/sandbox-api/integration-map.md
  - docs/sandbox-api/openapi.json
updated: 2026-07-06
---

# AIO Sandbox REST 契约

## 权威来源

AIO REST 契约以仓库内文件为准：

- `docs/sandbox-api/README.md`
- `docs/sandbox-api/integration-map.md`
- `docs/sandbox-api/openapi.json`

修改沙箱 HTTP 集成、文件预览、shell、浏览器或代理前，必须先阅读这些文件。

## 当前集成点

- Shell 会话和命令执行。
- 文件上传、读取、写入、预览、下载。
- 浏览器代理脚本和截图。
- Office 预览转换。
- MCP shell transport 中的 supergateway 启动和 JSON-RPC 转发。
- 用户级 Sandbox 健康检查。

## 沙箱视图代理与 AIO 契约

同源沙箱视图代理不是 AIO 原生契约的一部分，而是 WebAgent 在后端加的一层：

- 浏览器访问 `/sandbox-view/{token}/...`。
- 后端按用户查当前 AIO endpoint。
- HTTP 请求转发到 `http://{endpoint}{path}`。
- WebSocket Upgrade 转发到 `ws://{endpoint}{path}`。
- Location 头被改写回同源代理路径。

这层代理的目标是让 AIO 内部 noVNC、code-server、terminal 等页面在公网或云端部署时仍可访问。

## 维护规则

- 若 OpenAPI 与代码冲突，先以 OpenAPI 为准定位问题。
- 不要把内部 endpoint 暴露给浏览器作为长期接口。
- 修改代理路径时同时检查 HTTP 和 WebSocket 行为。
- 与沙箱文件、shell、浏览器相关的接口变动需要同步 [[后端 HTTP API]] 和 [[Sandbox 模块]]。

## 相关页面

[[AIO Sandbox 客户端]] · [[Sandbox 模块]] · [[用户 Sandbox 创建与恢复]]