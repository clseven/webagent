---
project: webagent-clean
type: module
status: verified
area:
  - tools
  - skills
  - rag
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/tool/SkillListTool.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillActivateTool.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillReferenceTool.java
  - src/main/java/com/example/sandbox/web/service/tool/KnowledgeSearchTool.java
  - src/main/java/com/example/sandbox/web/service/tool/WebSearchTool.java
  - src/main/java/com/example/sandbox/web/service/tool/McpAddOrUpdateServerTool.java
updated: 2026-07-09
---

# Skill 与知识检索工具

## 1. Skill 工具

Skill 系统采用渐进式披露，三个工具配合使用：

| 工具名 | 功能 | sideEffect |
| --- | --- | --- |
| `skill_list` | 查看可用 Skill 列表 | READ |
| `skill_activate` | 读取某个 Skill 的主指令（SKILL.md） | READ |
| `skill_reference` | 读取 Skill 附带的引用文件 | READ |

启用 Skill 的摘要（id + description）会进入系统提示，但完整指令需要工具读取。这样可以降低普通对话的提示词负担，也避免一次性注入全部技能内容。

## 2. 知识库检索

### knowledge_search

| 属性 | 值 |
| --- | --- |
| 工具名 | `knowledge_search` |
| sideEffect | READ |
| 功能 | 对知识库执行检索增强 |

应用关联知识库时，`AgentToolContextService` 会动态配置：

- 工具描述注入知识库描述（`buildKnowledgeDescription`）。
- 默认 kbId 取第一个关联知识库（`setCurrentKbId`）。
- 模型不需要自己猜 kbId。

检索走 `KnowledgeEnhancer` 的六步流程（Query Rewrite → 并行召回 → 去重 → Rerank → topK → 上下文）。SOCIAL 轮次不注入此工具。对话结束后 `clearRuntimeState` 清理动态配置。

RAG 细节见 [[RAG 知识库模块]] 和 [[查询改写与重排]]。

## 3. Web 搜索

### web_search

| 属性 | 值 |
| --- | --- |
| 工具名 | `web_search` |
| sideEffect | READ |
| 功能 | 外部网页搜索 |

当前浏览器操作提示中要求需要浏览器搜索网页时默认使用 Bing。`WebSearchTool` 用 Jsoup 解析搜索结果。受 `UserContext.isWebSearchEnabled()` 控制，关闭时不注入此工具。

## 4. MCP 管理工具

| 工具名 | 功能 | sideEffect |
| --- | --- | --- |
| `mcp_list_servers` | 列出已连接 MCP Server | READ |
| `mcp_add_or_update_server` | 添加或更新 MCP Server 配置 | EXCLUSIVE |
| `mcp_remove_server` | 移除 MCP Server | EXCLUSIVE |
| `mcp_reload` | 重载 MCP 连接 | EXCLUSIVE |

MCP 子系统未启用时，工具应提示用户开启 `agent.mcp.enabled=true` 并重启服务；不应自行修改配置文件。用户动态 MCP 默认要求 HTTPS，并默认禁止私网地址（`agent.mcp.user-allow-http=false`、`agent.mcp.user-allow-private-network=false`）。

## 5. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| skill_activate 读不到内容 | 沙箱 `/home/gem/skills/` 目录 | 运行期走沙箱，检查文件是否同步 |
| knowledge_search 无结果 | `rag.milvus.enabled`、应用知识库关联 | Milvus 默认关闭 |
| knowledge_search 描述不对 | `configureKnowledgeSearchTool`、app.knowledgeBaseIds | 动态描述在构建时注入 |
| web_search 不出现 | `UserContext.isWebSearchEnabled()` | 网络搜索开关 |
| MCP 工具不出现 | `agent.mcp.enabled`、MCP Server 配置 | 默认开启 |
| MCP 添加被拒 | URL 是否 HTTPS、是否私网地址 | 默认禁止 HTTP 和私网 |

## 6. 相关页面

[[Skill 系统模块]] · [[RAG 知识库模块]] · [[工具系统模块]] · [[运行配置]]