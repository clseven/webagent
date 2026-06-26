## 项目规范

- 完整规范：`docs/project-spec.md`
- 新增代码、重构、接入新能力前先看对应章节
- 有新的架构决策时在规范的第八章补一条 ADR

## 代码探索

理解代码结构时优先使用 codebase-memory-mcp，比 grep/glob 更准确：

- 找函数定义、调用链 → `search_graph`、`trace_path`
- 理解整体架构 → `get_architecture`
- 查函数源码 → `get_code_snippet`
- 分析改动影响 → `detect_changes`

## 核心规则

### 代码修改规则

- 永远不要直接修改代码。
- 修改前必须先与用户讨论问题和可选方案，并等待用户明确确认。
- 讨论时必须清晰说明：问题是什么、有哪些方案、推荐哪个方案，以及推荐理由。
- 未获得用户明确确认前，只允许读取、分析和提出建议，不得编辑、新建或删除文件。

### 沟通风格

- 使用中文沟通。
- 先分析问题，再提出建议。
- 完成修改后，必须清晰列出修改的文件、主要变化和验证结果。

## AIO Sandbox API

Before changing sandbox HTTP integrations, read:

- `docs/sandbox-api/README.md`
- `docs/sandbox-api/integration-map.md`
- `docs/sandbox-api/openapi.json` for exact request and response schemas

Treat the checked-in OpenAPI document as the authoritative AIO REST contract.

## Code Documentation

- 所有新增或修改的代码注释、Javadoc 和脚本注释必须使用中文；类名、方法名、参数名、API 名称和其他技术标识符可保留原文。
- Add clear comments or Javadoc for every newly created class, field, and method.
- Class comments must explain the class responsibility and its role in the system.
- Method comments must explain purpose, important parameters, return values, and exceptional behavior.
- Error handling and retry code must document which failures are retried, which are not retried, and why.
- Comments should explain intent and constraints instead of restating the code line by line.
