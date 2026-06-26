# CLAUDE.md

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
- **永远不要直接修改代码**
- 先讨论问题和方案，获得用户确认后再修改
- 讨论时要清晰说明：问题是什么、方案有哪些、推荐哪个、为什么

### 沟通风格
- 中文沟通
- 先分析后建议


### 沙箱相关
- AIO 沙箱 API: `/v1/*`
- 文件操作: `/v1/file/*`
- 浏览器: `/v1/browser/*`
- Shell: `/v1/shell/*`
- 完整 OpenAPI: `docs/sandbox-api/openapi.json`
- AI 导航说明: `docs/sandbox-api/README.md`
- Java 客户端映射: `docs/sandbox-api/integration-map.md`
