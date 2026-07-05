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
updated: 2026-07-06
---

# Skill 与知识检索工具

## Skill 工具

Skill 系统采用渐进式披露：

- `skill_list`：查看可用 Skill。
- `skill_activate`：读取某个 Skill 的主指令。
- `skill_reference`：读取 Skill 附带引用文件。

启用 Skill 的摘要会进入系统提示，但完整指令需要工具读取。这样可以降低普通对话的提示词负担，也避免一次性注入全部技能内容。

## 知识库检索

`knowledge_search` 会根据当前会话的应用配置和知识库上下文执行检索。应用关联知识库时，工具描述和默认知识库会被动态设置，模型不需要自己猜 kbId。

RAG 细节见 [[RAG 知识库模块]] 和 [[查询改写与重排]]。

## Web 搜索

`web_search` 提供外部网页搜索能力。当前浏览器操作提示中要求需要浏览器搜索网页时默认使用 Bing；WebSearchTool 本身仍是工具系统中的普通搜索工具。

## MCP 管理工具

MCP 管理工具包括：

- `mcp_list_servers`。
- `mcp_add_or_update_server`。
- `mcp_remove_server`。
- `mcp_reload`。

MCP 子系统未启用时，工具应提示用户开启 `agent.mcp.enabled=true` 并重启服务；不应自行修改配置文件。用户动态 MCP 默认要求 HTTPS，并默认禁止私网地址。

## 相关页面

[[Skill 系统模块]] · [[RAG 知识库模块]] · [[工具系统模块]] · [[运行配置]]