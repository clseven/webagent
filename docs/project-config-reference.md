# 项目配置参数参考手册

> 最后更新: 2026-05-30
>
> 本文档汇总了 sandbox-demo 项目中所有硬编码的配置参数、常量、阈值和限制。

---

## 目录

1. [application.yml 配置文件](#1-applicationyml-配置文件)
2. [AgentConfigProperties 配置属性类](#2-agentconfigproperties-配置属性类)
3. [Agent 核心参数](#3-agent-核心参数)
4. [LLM 调用参数](#4-llm-调用参数)
5. [沙箱管理参数](#5-沙箱管理参数)
6. [文件存储参数](#6-文件存储参数)
7. [认证与安全参数](#7-认证与安全参数)
8. [前端配置](#8-前端配置)
9. [数据库表结构约束](#9-数据库表结构约束)
10. [工具注册表](#10-工具注册表)
11. [硬编码路径汇总](#11-硬编码路径汇总)
12. [安全风险提示](#12-安全风险提示)

---

## 1. application.yml 配置文件

**文件**: `src/main/resources/application.yml`

### 1.1 应用基础配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `app.base-url` | `http://localhost:8081` | `APP_BASE_URL` | 应用基础 URL |
| `server.port` | `8081` | - | 服务端口 |
| `spring.application.name` | `sandbox-agent` | - | 应用名称 |
| `logging.level.com.example.sandbox` | `DEBUG` | - | 日志级别 |

### 1.2 数据库配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/sandbox_agent?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true` | `MYSQL_URL` | 数据库连接 URL |
| `spring.datasource.username` | `root` | `MYSQL_USERNAME` | 数据库用户名 |
| `spring.datasource.password` | `159357` | `MYSQL_PASSWORD` | 数据库密码 |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | - | JDBC 驱动 |
| `spring.jpa.hibernate.ddl-auto` | `update` | - | DDL 自动更新策略 |
| `spring.jpa.show-sql` | `false` | - | 是否打印 SQL |
| `spring.jpa.properties.hibernate.dialect` | `org.hibernate.dialect.MySQLDialect` | - | Hibernate 方言 |
| `spring.jpa.properties.hibernate.format_sql` | `true` | - | 格式化 SQL |

### 1.3 沙箱配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agent.sandbox.domain` | `localhost:8080` | `SANDBOX_DOMAIN` | OpenSandbox 服务域名 |
| `agent.sandbox.image` | `ghcr.io/agent-infra/sandbox:latest` | `SANDBOX_IMAGE` | 默认沙箱镜像 |
| `agent.sandbox.timeout` | `PT30M` | - | 沙箱最大存活时间（30 分钟） |
| `agent.sandbox.ready-timeout` | `PT180S` | - | 沙箱就绪等待超时（180 秒） |

### 1.4 存储配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agent.storage.type` | `local` | `STORAGE_TYPE` | 文件存储类型 |
| `agent.storage.local.base-path` | `./uploads` | `STORAGE_LOCAL_PATH` | 本地存储路径 |
| `agent.storage.oss.endpoint` | `""` | - | OSS 端点 |
| `agent.storage.oss.bucket` | `""` | - | OSS Bucket |
| `agent.storage.oss.access-key` | `""` | - | OSS Access Key |
| `agent.storage.oss.secret-key` | `""` | - | OSS Secret Key |

### 1.5 LLM 配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agent.llm.planner.api-url` | `https://api.deepseek.com` | `DEEPSEEK_LLM_URL` | 规划器 API 地址（DeepSeek） |
| `agent.llm.planner.api-key` | *(空，必须注入)* | `DEEPSEEK_API_KEY` | 规划器 API Key |
| `agent.llm.planner.model` | `deepseek-v4-pro` | `DEEPSEEK_LLM_MODEL` | 规划器模型 |
| `agent.llm.executor.api-url` | `https://api.deepseek.com` | `DEEPSEEK_LLM_URL` | 执行器 API 地址（DeepSeek） |
| `agent.llm.executor.api-key` | *(空，必须注入)* | `DEEPSEEK_API_KEY` | 执行器 API Key |
| `agent.llm.executor.model` | `deepseek-v4-pro` | `DEEPSEEK_LLM_MODEL` | 执行器模型 |

### 1.6 其他配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agent.skill.directory` | `.claude/skills` | `SKILL_DIR` | 技能文件目录 |
| `baidu.ocr.ak` | *(空，必须注入)* | `BAIDU_OCR_AK` | 百度 OCR Access Key |
| `baidu.ocr.sk` | *(空，必须注入)* | `BAIDU_OCR_SK` | 百度 OCR Secret Key |

### 1.7 Office 预览配置

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `rag.preview.conversion.enabled` | `true` | `RAG_PREVIEW_CONVERSION_ENABLED` | 是否启用 Office 转 PDF |
| `rag.preview.conversion.timeout-seconds` | `120` | `RAG_PREVIEW_CONVERSION_TIMEOUT_SECONDS` | LibreOffice 转换超时秒数 |

---

## 2. AgentConfigProperties 配置属性类

**文件**: `src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java`

该类通过 `@ConfigurationProperties(prefix = "agent")` 绑定 `application.yml` 中的配置，并提供 Java 代码中的默认值。

| 内部类 | 字段 | 代码默认值 | 说明 |
|--------|------|-----------|------|
| `Sandbox` | `domain` | `"localhost:8080"` | 沙箱域名 |
| `Sandbox` | `image` | `"sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2"` | 默认镜像 |
| `Sandbox` | `timeout` | `"PT30M"` | 超时时间 |
| `Sandbox` | `readyTimeout` | `"PT120S"` | 就绪超时 |
| `Skill` | `directory` | `".claude/skills"` | 技能目录 |
| `Planner` | `apiUrl` | `"https://open.bigmodel.cn/api/paas/v4"` | API 地址 |
| `Planner` | `model` | `"glm-4.7"` | 模型名称 |
| `Executor` | `apiUrl` | `"https://api.deepseek.com"` | API 地址 |
| `Executor` | `model` | `"deepseek-v4-flash"` | 模型名称 |
| `Storage` | `type` | `"local"` | 存储类型 |
| `Local` | `basePath` | `"./uploads"` | 本地路径 |

---

## 3. Agent 核心参数

### 3.1 ReactAgent — ReAct 循环

**文件**: `src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `MAX_ITERATIONS` | `200` | ReAct 单次运行最大迭代次数；达到后暂停并保存协议检查点 |
| `SUMMARIZE_THRESHOLD` | `200_000` | 估算消息总量超过 200K token 时触发摘要压缩 |
| `TOKEN_CHARS_RATIO` | `3` | Token 估算比例：每 3 个字符 ≈ 1 token |
| `TOOL_TIMEOUT` | `120 秒` | 单个工具执行超时 |
| Agent 内历史处理 | 不再按条数截断 | 普通历史由服务层加载最近 20 条；暂停检查点在 Agent 内完整保留 |
| 摘要保留比例 | `0.6` | 压缩时保留前 60% 的旧消息 |
| 最少消息保护 | `2` 条 | 少于 2 条消息时不触发压缩 |
| 单条消息截断 | `500` 字符 | 摘要生成时每条消息截取前 500 字符 |
| 摘要字数限制 | `500` 字 | `SUMMARIZE_PROMPT` 要求摘要不超过 500 字 |

### 3.2 AgentServiceImpl — Agent 编排

**文件**: `src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java`

| 参数 | 值 | 说明 |
|------|-----|------|
| 历史消息加载 | `20` 条 | `getRecentHistory(sessionId, 20)` |
| 文件上传目录 | `/home/gem/uploads/` | Agent 读取上传文件的沙箱路径 |

### 3.3 PlanAgent — 规划器

**文件**: `src/main/java/com/example/sandbox/web/service/impl/PlanAgent.java`

| 参数 | 说明 |
|------|------|
| `PLANNER_SYSTEM_PROMPT` | 规划器系统提示词，定义任务拆解规则和输出格式 |

### 3.4 ConversationServiceImpl — 会话服务

**文件**: `src/main/java/com/example/sandbox/web/service/impl/ConversationServiceImpl.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `SAFE_SKILL_ID` | `^[a-zA-Z0-9][a-zA-Z0-9_.\-]{0,63}$` | 技能 ID 安全校验正则，最大 63 字符 |

---

## 4. LLM 调用参数

### 4.1 BaseLlmServiceImpl — HTTP 超时

**文件**: `src/main/java/com/example/sandbox/web/service/impl/BaseLlmServiceImpl.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `CONNECT_TIMEOUT_MILLIS` | `10_000` (10 秒) | LLM API 连接超时 |
| `RESPONSE_TIMEOUT_SECONDS` | `300` (5 分钟) | LLM API 响应超时 |
| 错误回复文本 | `"抱歉，AI 服务暂时不可用，请稍后重试。"` | 调用失败时的用户提示 |

### 4.2 Token 追踪

**文件**: `src/main/java/com/example/sandbox/web/model/entity/TokenUsageEntity.java`

Token 用量记录字段：`promptTokens`、`completionTokens`、`cacheHitTokens`、`totalTokens`、`model`、`messageType`。

> **注意**: Token 用量仅记录到数据库，未做配额限制。

---

## 5. 沙箱管理参数

### 5.1 SandboxServiceImpl — 沙箱生命周期

**文件**: `src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `SANDBOX_TIMEOUT` | `24 小时` | 沙箱最大存活时间 |
| `RENEW_INTERVAL` | `30 分钟` | 沙箱续期间隔 |
| 定时任务频率 | `20 分钟` | `@Scheduled(fixedRate)` 续期任务间隔 |
| AIO 入口点 | `/opt/gem/run.sh` | AIO 沙箱启动脚本路径 |
| AIO 镜像关键词 | `agent-infra/sandbox` 或 `all-in-one-sandbox` | 判断镜像类型 |
| 初始化目录 | `/home/gem/{uploads,workspace,output,skills,temp}` | AIO 沙箱初始目录 |

### 5.2 SandboxAgent — 沙箱 Agent

**文件**: `src/main/java/com/example/sandbox/agent/SandboxAgent.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_IMAGE` | `sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/chrome:latest` | Chrome 沙箱镜像 |
| `DEFAULT_TIMEOUT` | `30 分钟` | 沙箱超时 |
| `DEFAULT_READY_TIMEOUT` | `60 秒` | 就绪超时 |
| `WORK_DIR` | `/home/gem` | 沙箱工作目录 |
| 文件权限 | `644` | 默认文件写入权限 |
| 脚本权限 | `755` | 脚本文件权限 |

### 5.3 AioClient — AIO 聚合客户端

**文件**: `src/main/java/com/example/sandbox/aio/AioClient.java`

底层 HTTP 调用集中在 `aio/core/AioHttpClient.java`，业务调用按
`sandbox`、`shell`、`file`、`browser`、`node`、`utility` 领域拆分。

| 常量 | 值 | 说明 |
|------|-----|------|
| `AioHttpClient.DEFAULT_TIMEOUT` | `120 秒` | HTTP 响应超时 |
| `AioHttpClient.MAX_IN_MEMORY_SIZE` | `16 MB` | 单次响应内存缓冲上限 |
| 健康检查超时 | `5 秒` | 首次健康检查超时 |
| `AioClient.waitForReady` | `120 秒` | 新建沙箱等待就绪超时 |
| 首次失败重试等待 | `60 秒` | 检测失败后的重试等待 |
| Browser Agent 运行时 | `/home/gem/.runtime/browser-agent` | 新沙箱创建时安装，不展示在用户工作空间 |

### 5.4 PlaywrightBaiduTest — 测试参数

**文件**: `src/main/java/com/example/sandbox/playwright/PlaywrightBaiduTest.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_DOMAIN` | `localhost:8080` | OpenSandbox 地址 |
| `PLAYWRIGHT_IMAGE` | `sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/playwright:latest` | Playwright 镜像 |
| `DEFAULT_TIMEOUT` | `30 分钟` | 沙箱超时 |
| `DEFAULT_READY_TIMEOUT` | `120 秒` | 就绪超时 |
| `WORK_DIR` | `/home/playwright` | 工作目录 |
| 连接超时 | `5 分钟` / `5000 ms` | 连接和读取超时 |
| 脚本执行超时 | `60 秒` | Shell 脚本超时 |

---

## 6. 文件存储参数

| 文件 | 常量 | 值 | 说明 |
|------|------|-----|------|
| `LocalFileStorageServiceImpl.java` | `MOUNT_PATH` | `/home/gem/uploads` | 容器内挂载路径 |
| `OssFileStorageServiceImpl.java` | `MOUNT_PATH` | `/home/gem/uploads` | 容器内挂载路径（OSS） |
| `FileSyncService.java` | 上传同步路径 | `/home/gem/uploads` | 同步到沙箱的目标路径 |
| `FileSyncService.java` | 技能同步路径 | `/home/gem/skills/{skillId}` | 技能文件同步路径 |
| `FileUploadController.java` | 上传目标路径 | `/home/gem/uploads/` | AIO 沙箱上传路径 |

用户级持久化目录：

```text
uploads/users/{userId}/knowledge/{kbId}/{fileName}
uploads/users/{userId}/uploads/{fileName}
```

新沙箱创建后，上述目录分别以二进制方式恢复到：

```text
/home/gem/knowledge/{kbId}/{fileName}
/home/gem/uploads/{fileName}
```

---

## 7. 认证与安全参数

### 7.1 AuthFilter

**文件**: `src/main/java/com/example/sandbox/web/config/AuthFilter.java`

| 参数 | 值 | 说明 |
|------|-----|------|
| 白名单路径 | `/api/auth/login`, `/api/auth/register` | 无需认证的路径 |
| API 路径前缀 | `/api/` | 需要认证的路径前缀 |

### 7.2 WebConfig

**文件**: `src/main/java/com/example/sandbox/web/config/WebConfig.java`

| 参数 | 值 | 说明 |
|------|-----|------|
| URL 模式 | `/api/*` | AuthFilter 拦截模式 |
| 过滤器顺序 | `1` | 执行优先级 |

---

## 8. 前端配置

### 8.1 api.js — API 客户端

**文件**: `src/main/resources/static/js/api.js`

| 参数 | 值 | 说明 |
|------|-----|------|
| `API_BASE` | `''` (空) | API 基础地址（同源请求） |
| 认证失效状态码 | `401` | 清除 Token 的触发条件 |
| 成功状态码 | `200` | 非 200 抛出错误 |

### 8.2 app.js — 应用入口

**文件**: `src/main/resources/static/js/app.js`

| 参数 | 值 | 说明 |
|------|-----|------|
| 本地存储键 | `auth_token` | Token 存储键名 |
| 本地存储键 | `username` | 用户名存储键名 |
| 本地存储键 | `agent_session_id` | 当前会话 ID 键名 |
| 路由表 | `/`, `/login`, `/chat`, `/skills`, `/knowledge`, `/mcp`, `/token-stats` | 前端路由 |

### 8.3 Chat.js — 对话页面

**文件**: `src/main/resources/static/js/pages/Chat.js`

| 参数 | 值 | 说明 |
|------|-----|------|
| VNC 默认宽度 | `70%` | VNC 面板初始宽度 |
| VNC 宽度限制 | `30% - 90%` | 按钮调整范围 |
| 拖拽宽度限制 | `20% - 80%` | 拖拽调整范围 |
| 复制状态重置 | `1500 ms` | 复制按钮文字恢复时间 |
| Markdown 配置 | `breaks: true, gfm: true` | marked 渲染选项 |

### 8.4 TokenStats.js — Token 统计

**文件**: `src/main/resources/static/js/pages/TokenStats.js`

| 参数 | 值 | 说明 |
|------|-----|------|
| 默认时间范围 | `7` 天 | 统计默认显示范围 |
| 时间范围选项 | `1, 7, 30, 90` 天 | 可选范围 |
| 饼图颜色 | `['#4CAF50', '#2196F3', '#FF9800', ...]` | 最多 8 种颜色 |

### 8.5 WorkspaceBrowser.js — 工作空间

**文件**: `src/main/resources/static/js/components/WorkspaceBrowser.js`

| 参数 | 值 | 说明 |
|------|-----|------|
| 根目录 | `/home/gem` | 浏览器根目录 |
| 根目录判断 | `parts.length <= 3` | 路径深度判断 |
| 文件图标映射 | 24 种扩展名 | 文件类型图标 |

### 8.6 CDN 依赖版本

**文件**: `src/main/resources/static/index.html`

| 库 | 版本 | 说明 |
|-----|------|------|
| Vue | `vue@3` | 前端框架 |
| Vue Router | `vue-router@4` | 路由 |
| marked | `4.3.0` | Markdown 渲染 |
| highlight.js | `11.9.0` | 代码高亮 |
| Chart.js | `4.4.0` | 图表 |

---

## 9. 数据库表结构约束

### 9.1 users 表

**文件**: `src/main/java/com/example/sandbox/web/model/entity/UserEntity.java`

| 字段 | 约束 | 说明 |
|------|------|------|
| `id` | `BIGINT AUTO_INCREMENT` | 主键 |
| `username` | `VARCHAR(64) UNIQUE NOT NULL` | 用户名 |
| `password_hash` | `VARCHAR(128) NOT NULL` | 密码哈希 |
| `token` | `VARCHAR(64) UNIQUE` | 认证 Token |
| 索引 | `idx_users_token`, `idx_users_username` (UNIQUE) | - |

### 9.2 conversation_session 表

**文件**: `src/main/java/com/example/sandbox/web/model/entity/ConversationSessionEntity.java`

| 字段 | 约束 | 说明 |
|------|------|------|
| `id` | `VARCHAR(36)` | UUID 主键 |
| `sandbox_id` | `VARCHAR(128)` | 沙箱 ID |
| `aio_endpoint` | `VARCHAR(64)` | AIO 端点 |
| 关联表 | `session_skill` (skill_id VARCHAR(64)) | 会话-技能关联 |

### 9.3 chat_message 表

**文件**: `src/main/java/com/example/sandbox/web/model/entity/ChatMessageEntity.java`

| 字段 | 约束 | 说明 |
|------|------|------|
| `session_id` | 索引 `idx_session_id` | 会话 ID |
| `role` | `VARCHAR(32)` | 角色 |
| `content` | `TEXT` | 消息内容（无长度限制） |
| `reasoning` | `TEXT` | 模型思考内容 |
| `events_json` | `LONGTEXT` | 前端展示与审计使用的执行过程事件，不直接作为协议上下文 |
| `run_status` | `VARCHAR(32)` | Agent 运行状态；`PAUSED_MAX_ITERATIONS` 表示可继续的暂停运行 |
| `checkpoint_json` | `LONGTEXT` | 暂停时保存的角色、tool_call ID、tool 结果顺序等协议检查点 |
| `timestamp` | 索引 `idx_timestamp` | 时间戳 |

### 9.4 token_usage 表

**文件**: `src/main/java/com/example/sandbox/web/model/entity/TokenUsageEntity.java`

| 字段 | 约束 | 说明 |
|------|------|------|
| `user_id` | 索引 `idx_token_user_id` | 用户 ID |
| `session_id` | `VARCHAR(36)`, 索引 | 会话 ID |
| `prompt_tokens` | `INT NOT NULL` | 输入 Token 数 |
| `completion_tokens` | `INT NOT NULL` | 输出 Token 数 |
| `cache_hit_tokens` | `INT NOT NULL` | 缓存命中 Token 数 |
| `total_tokens` | `INT NOT NULL` | 总 Token 数 |
| `model` | `VARCHAR(50)` | 模型名称 |
| `message_type` | `VARCHAR(20)` | 消息类型 |
| `created_at` | 索引 `idx_token_created_at` | 创建时间 |

---

## 10. 工具注册表

LLM 可调用的工具清单：

| 工具名 | 类名 | 沙箱类型 | 说明 |
|--------|------|----------|------|
| `execute_command` | ExecuteCommandTool | ALL | 执行 Shell 命令 |
| `read_file` | ReadFileTool | ALL | 读取文件 |
| `write_file` | WriteFileTool | ALL | 写入文件 |
| `list_files` | ListFilesTool | ALL | 列出文件 |
| `file_replace` | FileReplaceTool | ALL | 文件内容替换 |
| `file_search` | FileSearchTool | ALL | 文件搜索 |
| `str_replace_editor` | StrReplaceEditorTool | ALL | 字符串替换编辑 |
| `convert_to_markdown` | ConvertToMarkdownTool | ALL | 转换为 Markdown |
| `request_sandbox` | RequestSandboxTool | ALL | 请求新沙箱 |
| `skill_list` | SkillListTool | ALL | 列出技能 |
| `skill_activate` | SkillActivateTool | ALL | 激活技能 |
| `skill_reference` | SkillReferenceTool | ALL | 引用技能 |
| `download_file` | DownloadFileTool | AIO | 下载文件 |
| `browser_action` | BrowserActionTool | AIO | 浏览器操作 |
| `browser_screenshot` | BrowserScreenshotTool | AIO | 浏览器截图 |
| `browser_info` | BrowserInfoTool | AIO | 浏览器信息 |
| `shell_wait` | ShellWaitTool | AIO | 等待 Shell 命令完成（默认 30 秒） |
| `shell_kill` | ShellKillTool | AIO | 终止 Shell 进程 |

---

## 11. 硬编码路径汇总

| 路径 | 出现位置 | 说明 |
|------|----------|------|
| `/home/gem` | SandboxAgent, ReactAgent, WorkspaceBrowser.js | 沙箱根工作目录 |
| `/home/gem/uploads/` | ReactAgent, AgentServiceImpl, FileSyncService, FileUploadController, LocalFileStorageServiceImpl, OssFileStorageServiceImpl | 用户上传文件目录 |
| `/home/gem/workspace/` | ReactAgent | 工作目录 |
| `/home/gem/output/` | ReactAgent | 输出结果目录 |
| `/home/gem/skills/{id}/` | ReactAgent, FileSyncService, ConversationServiceImpl | 技能文件目录 |
| `/home/gem/temp/` | ReactAgent | 临时文件目录 |
| `/home/gem/knowledge/{kbId}/.preview/` | OfficePreviewService | 知识库 Office PDF 缓存 |
| `/home/gem/temp/previews/` | OfficePreviewService | 普通文件 Office PDF 缓存 |
| `/home/playwright` | PlaywrightBaiduTest | Playwright 沙箱工作目录 |
| `/opt/gem/run.sh` | SandboxServiceImpl | AIO 沙箱启动脚本 |
| `SKILL.md` | SkillServiceImpl | 技能元数据文件名 |

---

## 12. 安全风险提示

### 高风险 — 敏感信息管理

以下配置在 `application.yml` 中不提供默认值，必须通过环境变量或密钥管理服务注入：

| 配置项 | 说明 |
|--------|------|
| `agent.llm.planner.api-key` | 智谱 AI API Key |
| `agent.llm.executor.api-key` | DeepSeek API Key |
| `baidu.ocr.ak` / `baidu.ocr.sk` | 百度 OCR 密钥对 |
| `spring.datasource.password` | 数据库密码 |

### 中风险 — 性能/稳定性参数

| 参数 | 当前值 | 风险说明 |
|------|--------|----------|
| ReAct 最大迭代 | 20 次 | 复杂任务可能不够 |
| Token 压缩阈值 | 24,000 字符 | 长对话可能丢失上下文 |
| LLM 响应超时 | 300 秒 | 大模型推理可能超时 |
| 工具执行超时 | 120 秒 | 复杂操作可能不够 |
| 沙箱最大存活 | 24 小时 | 资源占用 |

### 低风险 — 功能性默认值

各种路径、端口、镜像名、UI 参数等，属于业务默认值，按需调整即可。
