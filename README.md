# Sandbox Agent

一个基于 Spring Boot 的 Web 端 Agent 系统，集成了沙箱隔离环境和大语言模型，支持技能（Skills）扩展和知识库（RAG）检索。

## 技术栈

- **后端**: Spring Boot 3.2.5 + Java 17
- **前端**: Vue 3 + Vue Router 4
- **数据库**: MySQL + Spring Data JPA
- **向量数据库**: Milvus
- **沙箱**: OpenSandbox SDK（连接 `localhost:8080`）
- **LLM**: 智谱 GLM-4 / DeepSeek
- **Embedding**: 智谱 embedding-3 (1024 维)
- **构建**: Maven

## 核心功能

### 1. Agent 应用

用户可以创建自定义的 Agent 应用，每个应用可以独立配置：

- **关联知识库**：选择已有的知识库，Agent 会根据知识库描述自动判断何时检索
- **关联技能**：选择启用哪些 Skill，不启用的技能不会出现在 Agent 的工具列表中
- **会话隔离**：每个会话绑定到一个 Agent 应用，继承应用的配置

### 2. ReAct Agent

实现了 ReAct（Reasoning + Acting）模式，双 LLM 架构：

- **规划器（Planner）**：使用 GLM-4 分析用户意图，产出结构化执行计划
- **执行器（Executor）**：使用 DeepSeek 按 ReAct 循环执行，逐步调用工具

### 3. 知识库（RAG）

支持多知识库管理，每个知识库可包含多个文档：

- **文档解析**：支持 PDF、Word、Excel、PPT、TXT、Markdown、HTML、图片等格式
- **智能切片**：支持智能切片和自定义切片两种模式
- **向量检索**：使用 Milvus 存储向量，余弦相似度检索
- **知识库描述**：每个知识库有描述，Agent 根据描述判断是否需要检索

### 4. 沙箱隔离

每个用户关联一个独立的沙箱环境，通过 OpenSandbox 实现：
- 命令执行隔离
- 文件系统隔离
- 浏览器操作
- VNC 实时视图
- 知识库与普通上传文件在沙箱重建后自动恢复

用户文件以本地目录作为持久化主副本：

```text
uploads/users/{userId}/knowledge/{kbId}/{fileName}
uploads/users/{userId}/uploads/{fileName}
```

沙箱内对应路径为 `/home/gem/knowledge/{kbId}/{fileName}` 和
`/home/gem/uploads/{fileName}`。PDF、图片、文本、Markdown、CSV 和代码
直接预览；Office/OpenDocument 文件由 LibreOffice 转换为 PDF 后预览。
知识库预览保留“文件 + 数据库切片”双栏，普通工作空间文件保持单栏。

### 5. 技能系统

技能通过文件系统管理，渐进式披露使用：

- `skill_list` — 列出所有可用技能（简历模式）
- `skill_activate` — 激活技能，加载完整指令
- `skill_reference` — 读取技能的引用文件

### 6. 工具集

Agent 可调用的工具包括：

| 工具 | 功能 |
|------|------|
| `execute_command` | 在沙箱中执行命令行 |
| `read_file` | 读取沙箱中的文件 |
| `write_file` | 写入/编辑沙箱中的文件 |
| `list_files` | 列出沙箱目录结构 |
| `file_search` | 搜索文件 |
| `file_replace` | 文件内容替换 |
| `str_replace_editor` | 字符串替换编辑器 |
| `browser_action` | 浏览器操作 |
| `browser_screenshot` | 浏览器截图 |
| `request_sandbox` | 请求创建/获取沙箱 |
| `skill_list` | 列出可用技能 |
| `skill_activate` | 激活指定技能 |
| `skill_reference` | 读取技能引用文件 |
| `knowledge_search` | 知识库向量检索 |

## 项目结构

```
src/main/java/com/example/sandbox/web/
├── controller/          # REST API 控制器
│   ├── AgentController      # 会话与对话 API
│   ├── AgentAppController   # Agent 应用管理 API
│   ├── SkillController      # 技能管理 API
│   ├── SandboxController    # 沙箱管理 API
│   ├── RagController        # 知识库与 RAG API
│   └── FileUploadController # 文件上传 API
├── service/             # 业务服务层
│   ├── impl/
│   │   ├── AgentServiceImpl       # Agent 编排
│   │   ├── AgentAppServiceImpl    # Agent 应用管理
│   │   ├── ReactAgent             # ReAct 核心逻辑
│   │   ├── PlanAgent              # 规划器
│   │   ├── ConversationServiceImpl # 会话管理
│   │   ├── KnowledgeServiceImpl   # 知识库管理
│   │   ├── SkillServiceImpl       # 技能加载
│   │   ├── SandboxServiceImpl     # 沙箱生命周期
│   │   ├── VectorStoreServiceImpl # Milvus 向量存储
│   │   ├── EmbeddingServiceImpl   # Embedding 服务
│   │   └── DeepSeekLlmServiceImpl # DeepSeek LLM 调用
│   └── tool/            # Agent 可用工具
│       ├── ExecuteCommandTool
│       ├── ReadFileTool
│       ├── WriteFileTool
│       ├── KnowledgeSearchTool
│       └── ...
├── model/               # 数据模型
│   ├── entity/
│   │   ├── AgentAppEntity          # Agent 应用
│   │   ├── KnowledgeBaseEntity     # 知识库
│   │   ├── KnowledgeDocumentEntity # 知识库文档
│   │   ├── KnowledgeChunkEntity    # 知识切片
│   │   ├── ConversationSessionEntity # 会话
│   │   └── ...
│   └── response/
│       ├── AgentAppResponse
│       ├── KnowledgeBaseResponse
│       ├── DocumentResponse
│       └── ...
└── config/              # 配置类
    ├── MilvusConfig         # Milvus 向量数据库配置
    ├── RagConfigProperties  # RAG 配置
    └── AgentConfigProperties # Agent 配置
```

## API 接口

### Agent 应用 (`/api/apps`)

- `POST /` — 创建应用
- `GET /` — 列出用户应用
- `GET /{appId}` — 获取应用详情
- `PUT /{appId}` — 更新应用
- `DELETE /{appId}` — 删除应用
- `PUT /{appId}/knowledge-bases` — 关联知识库
- `PUT /{appId}/skills` — 关联技能

### 会话管理 (`/api/sessions`)

- `POST /` — 创建会话（可传 `appId` 关联应用）
- `GET /` — 列出用户会话
- `GET /{id}` — 获取会话信息
- `DELETE /{id}` — 关闭会话
- `POST /{id}/chat` — 发送消息
- `GET /{id}/history` — 获取历史消息
- `GET /{id}/skills` — 获取启用的技能
- `POST /{id}/skills/{skillId}/enable` — 启用技能
- `POST /{id}/skills/{skillId}/disable` — 禁用技能

### 知识库 (`/api/rag`)

- `POST /bases` — 创建知识库
- `GET /bases` — 列出用户知识库
- `GET /bases/{kbId}` — 获取知识库详情
- `PUT /bases/{kbId}` — 更新知识库
- `DELETE /bases/{kbId}` — 删除知识库
- `POST /bases/{kbId}/documents/upload` — 上传文档到知识库
- `GET /bases/{kbId}/documents` — 列出知识库下的文档
- `DELETE /document/{docId}` — 删除文档
- `POST /bases/{kbId}/search` — 在知识库中检索

### 技能管理 (`/api/skills`)

- `GET /` — 列出所有技能
- `GET /{id}` — 获取技能详情
- `POST /set-root` — 设置技能根目录

### 文件上传 (`/api/files`)

- `POST /upload` — 上传文件到会话

## 配置

在 `src/main/resources/application.yml` 中配置：

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/sandbox_agent

agent:
  sandbox:
    domain: localhost:8080
    image: agent-infra/sandbox-office:latest
  storage:
    type: local
  skill:
    directory: .claude/skills
  llm:
    planner:
      provider: zhipu
      api-url: https://open.bigmodel.cn/api/paas/v4
      api-key: your-api-key
      model: glm-4.7
    executor:
      provider: deepseek
      api-url: https://api.deepseek.com
      api-key: your-api-key
      model: deepseek-chat

rag:
  preview:
    conversion:
      enabled: true
      timeout-seconds: 120
  embedding:
    api-url: https://open.bigmodel.cn/api/paas/v4
    api-key: your-api-key
    model: embedding-3
    dimension: 1024
  milvus:
    host: localhost
    port: 19530
    collection: knowledge_chunks
  storage:
    path: ./uploads/knowledge
```

### 构建 LibreOffice 沙箱镜像

项目默认使用基于 AIO 沙箱扩展的本地镜像。首次启动前构建：

```bash
docker build -t agent-infra/sandbox-office:latest docker/sandbox-office
```

验证 LibreOffice 和 AIO 启动脚本：

```bash
docker run --rm --entrypoint soffice agent-infra/sandbox-office:latest --version
docker run --rm --entrypoint sh agent-infra/sandbox-office:latest -c "test -x /opt/gem/run.sh"
```

镜像名保留了 `agent-infra/sandbox`，以便项目继续将它识别为 AIO 沙箱。
派生镜像保留基础 AIO 镜像的默认 root 用户和 `/opt/gem/run.sh` 入口。
如果 OpenSandbox 服务运行在其他主机，需要把镜像推送到该主机可访问的镜像仓库，
并通过 `SANDBOX_IMAGE` 设置完整镜像地址。

## 启动

```bash
mvn spring-boot:run
```

访问 `http://localhost:8081` 打开前端界面。

本地开发前端时，使用 `dev` Profile 启动：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

该模式直接读取 `src/main/resources/static/` 并关闭静态资源缓存。
修改 HTML、CSS 或 JavaScript 后只需刷新浏览器，不需要重启 Spring 服务。
修改镜像或重启服务后，需要新建沙箱；已经运行的沙箱不会自动更换镜像。

## 使用流程

1. **创建知识库**：在知识库页面创建知识库，填写名称和描述
2. **上传文档**：选择知识库，上传文档（支持多种格式）
3. **创建 Agent 应用**：在 Agent 应用页面创建应用，关联知识库和技能
4. **开始对话**：在对话页面选择 Agent 应用，创建会话开始对话
