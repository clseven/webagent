# 代码重构计划

> 创建时间：2024-06-04
> 最后更新：2024-06-04
> 状态：**文件迁移已完成，待修复 import 引用**

---

## 一、重构目标

1. **统一使用 Lombok** - 减少样板代码约 200+ 行
2. **整理目录结构** - 按领域划分，职责清晰
3. **修复架构问题** - Controller 注入实现类、方法过长等

---

## 二、新目录结构

```
com.example.sandbox
├── SandboxApplication.java              # 启动类（移到根目录）
│
├── common/                              # 公共组件
│   ├── config/                          # 配置
│   │   ├── WebConfig.java
│   │   ├── AuthFilter.java
│   │   ├── GlobalExceptionHandler.java
│   │   ├── MilvusConfig.java
│   │   └── properties/
│   │       ├── AgentConfigProperties.java
│   │       └── RagConfigProperties.java
│   ├── context/
│   │   └── UserContext.java
│   └── exception/
│       ├── SessionNotFoundException.java
│       ├── SkillNotFoundException.java
│       └── UnauthorizedException.java
│
├── sandbox/                             # 沙箱领域
│   ├── client/
│   │   ├── SandboxClient.java           # 接口
│   │   ├── AioSandboxClient.java
│   │   ├── OpensandboxClient.java
│   │   └── SandboxClientFactory.java
│   ├── store/
│   │   └── AioSandboxStore.java
│   ├── agent/
│   │   ├── SandboxAgent.java
│   │   ├── PlanAgent.java
│   │   └── ReactAgent.java
│   └── service/
│       ├── SandboxService.java
│       └── SandboxServiceImpl.java
│
├── agent/                               # Agent 编排领域
│   ├── service/
│   │   ├── AgentService.java
│   │   └── AgentServiceImpl.java
│   └── model/
│       ├── AgentResponse.java
│       ├── AgentStep.java
│       ├── PlanResult.java
│       └── ReactResult.java
│
├── tool/                                # 工具领域
│   ├── Tool.java                        # 接口
│   ├── ToolDefinition.java
│   ├── base/
│   │   └── AbstractTool.java            # 新增：抽象基类
│   └── impl/
│       ├── file/
│       │   ├── ReadFileTool.java
│       │   ├── WriteFileTool.java
│       │   ├── ListFilesTool.java
│       │   ├── FileReplaceTool.java
│       │   ├── FileSearchTool.java
│       │   ├── DownloadFileTool.java
│       │   ├── ConvertToMarkdownTool.java
│       │   └── DocumentParserTool.java
│       ├── shell/
│       │   ├── ExecuteCommandTool.java
│       │   ├── ShellWaitTool.java
│       │   └── ShellKillTool.java
│       ├── browser/
│       │   ├── BrowserActionTool.java
│       │   ├── BrowserScreenshotTool.java
│       │   └── BrowserInfoTool.java
│       ├── skill/
│       │   ├── SkillListTool.java
│       │   ├── SkillActivateTool.java
│       │   └── SkillReferenceTool.java
│       ├── knowledge/
│       │   └── KnowledgeSearchTool.java
│       └── sandbox/
│           └── RequestSandboxTool.java
│
├── llm/                                 # LLM 领域
│   ├── LlmService.java
│   ├── model/
│   │   ├── LlmRequest.java
│   │   ├── LlmResponse.java
│   │   ├── LlmMessage.java
│   │   ├── LlmChoice.java
│   │   ├── LlmCompletionResponse.java
│   │   ├── LlmStreamChunk.java
│   │   ├── LlmToolCall.java
│   │   ├── LlmToolResult.java
│   │   └── LlmUsage.java
│   └── impl/
│       ├── BaseLlmServiceImpl.java
│       ├── DeepSeekLlmServiceImpl.java
│       └── ZhipuLlmServiceImpl.java
│
├── knowledge/                           # 知识库领域
│   ├── service/
│   │   ├── KnowledgeService.java
│   │   ├── EmbeddingService.java
│   │   ├── VectorStoreService.java
│   │   ├── DocumentParserService.java
│   │   ├── TextSplitterService.java
│   │   └── impl/
│   │       ├── KnowledgeServiceImpl.java
│   │       ├── EmbeddingServiceImpl.java
│   │       ├── VectorStoreServiceImpl.java
│   │       ├── DocumentParserServiceImpl.java
│   │       └── TextSplitterServiceImpl.java
│   ├── repository/
│   │   ├── KnowledgeBaseRepository.java
│   │   ├── KnowledgeChunkRepository.java
│   │   └── KnowledgeDocumentRepository.java
│   └── entity/
│       ├── KnowledgeBaseEntity.java
│       ├── KnowledgeChunkEntity.java
│       └── KnowledgeDocumentEntity.java
│
├── skill/                               # 技能领域
│   ├── service/
│   │   ├── SkillService.java
│   │   ├── FileSyncService.java
│   │   └── impl/
│   │       ├── SkillServiceImpl.java
│   │       └── FileSyncService.java
│   └── model/
│       └── Skill.java
│
├── user/                                # 用户领域
│   ├── service/
│   │   ├── UserService.java
│   │   └── impl/
│   │       └── UserServiceImpl.java
│   ├── repository/
│   │   └── UserRepository.java
│   └── entity/
│       └── UserEntity.java
│
├── conversation/                        # 会话领域
│   ├── service/
│   │   ├── ConversationService.java
│   │   └── impl/
│   │       └── ConversationServiceImpl.java
│   ├── repository/
│   │   ├── ConversationSessionRepository.java
│   │   ├── ChatMessageRepository.java
│   │   └── TokenUsageRepository.java
│   └── entity/
│       ├── ConversationSessionEntity.java
│       ├── ConversationSession.java
│       ├── ChatMessageEntity.java
│       ├── ChatMessage.java
│       ├── TokenUsageEntity.java
│       └── UserSandboxEntity.java
│
├── app/                                 # Agent 应用领域
│   ├── service/
│   │   ├── AgentAppService.java
│   │   └── impl/
│   │       └── AgentAppServiceImpl.java
│   ├── repository/
│   │   └── AgentAppRepository.java
│   └── entity/
│       └── AgentAppEntity.java
│
├── storage/                             # 文件存储领域
│   ├── FileStorageService.java
│   └── impl/
│       ├── LocalFileStorageServiceImpl.java
│       └── OssFileStorageServiceImpl.java
│
└── api/                                 # API 层
    ├── controller/
    │   ├── AgentController.java
    │   ├── SandboxController.java
    │   ├── AuthController.java
    │   ├── AgentAppController.java
    │   ├── FileUploadController.java
    │   ├── RagController.java
    │   ├── SkillController.java
    │   └── TokenStatsController.java
    ├── dto/
    │   ├── request/
    │   │   ├── ChatRequest.java
    │   │   ├── ExecuteRequest.java
    │   │   ├── FileWriteRequest.java
    │   │   ├── AuthRequest.java
    │   │   └── SearchRequest.java
    │   └── response/
    │       ├── ApiResponse.java
    │       ├── SessionResponse.java
    │       ├── AuthResponse.java
    │       ├── AgentAppResponse.java
    │       ├── DocumentResponse.java
    │       └── KnowledgeBaseResponse.java
    └── sse/
        └── SseEvent.java
```

---

## 三、文件迁移映射表

### 3.1 common 包

| 原路径 | 新路径 |
|--------|--------|
| `web/SandboxAgentApplication.java` | `SandboxApplication.java` |
| `web/config/WebConfig.java` | `common/config/WebConfig.java` |
| `web/config/AuthFilter.java` | `common/config/AuthFilter.java` |
| `web/config/GlobalExceptionHandler.java` | `common/config/GlobalExceptionHandler.java` |
| `web/config/MilvusConfig.java` | `common/config/MilvusConfig.java` |
| `web/config/AgentConfigProperties.java` | `common/config/properties/AgentConfigProperties.java` |
| `web/config/RagConfigProperties.java` | `common/config/properties/RagConfigProperties.java` |
| `web/context/UserContext.java` | `common/context/UserContext.java` |
| `web/exception/*.java` | `common/exception/*.java` |

### 3.2 sandbox 包

| 原路径 | 新路径 |
|--------|--------|
| `agent/SandboxAgent.java` | `sandbox/agent/SandboxAgent.java` |
| `agent/AgentDemo.java` | `sandbox/agent/AgentDemo.java` |
| `aio/AioSandboxClient.java` | `sandbox/client/AioSandboxClient.java` |
| `web/service/SandboxClient.java` | `sandbox/client/SandboxClient.java` |
| `web/service/impl/OpensandboxClient.java` | `sandbox/client/OpensandboxClient.java` |
| `web/service/impl/SandboxClientFactory.java` | `sandbox/client/SandboxClientFactory.java` |
| `web/service/impl/AioSandboxStore.java` | `sandbox/store/AioSandboxStore.java` |
| `web/service/impl/PlanAgent.java` | `sandbox/agent/PlanAgent.java` |
| `web/service/impl/ReactAgent.java` | `sandbox/agent/ReactAgent.java` |
| `web/service/SandboxService.java` | `sandbox/service/SandboxService.java` |
| `web/service/impl/SandboxServiceImpl.java` | `sandbox/service/SandboxServiceImpl.java` |

### 3.3 agent 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/AgentService.java` | `agent/service/AgentService.java` |
| `web/service/impl/AgentServiceImpl.java` | `agent/service/AgentServiceImpl.java` |
| `web/model/entity/PlanResult.java` | `agent/model/PlanResult.java` |
| `web/model/entity/ReactResult.java` | `agent/model/ReactResult.java` |
| `web/model/llm/AgentResponse.java` | `agent/model/AgentResponse.java` |
| `web/model/llm/AgentStep.java` | `agent/model/AgentStep.java` |

### 3.4 tool 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/Tool.java` | `tool/Tool.java` |
| `web/model/entity/ToolDefinition.java` | `tool/ToolDefinition.java` |
| `web/service/tool/ReadFileTool.java` | `tool/impl/file/ReadFileTool.java` |
| `web/service/tool/WriteFileTool.java` | `tool/impl/file/WriteFileTool.java` |
| `web/service/tool/ListFilesTool.java` | `tool/impl/file/ListFilesTool.java` |
| `web/service/tool/FileReplaceTool.java` | `tool/impl/file/FileReplaceTool.java` |
| `web/service/tool/FileSearchTool.java` | `tool/impl/file/FileSearchTool.java` |
| `web/service/tool/DownloadFileTool.java` | `tool/impl/file/DownloadFileTool.java` |
| `web/service/tool/ConvertToMarkdownTool.java` | `tool/impl/file/ConvertToMarkdownTool.java` |
| `web/service/tool/DocumentParserTool.java` | `tool/impl/file/DocumentParserTool.java` |
| `web/service/tool/ExecuteCommandTool.java` | `tool/impl/shell/ExecuteCommandTool.java` |
| `web/service/tool/ShellWaitTool.java` | `tool/impl/shell/ShellWaitTool.java` |
| `web/service/tool/ShellKillTool.java` | `tool/impl/shell/ShellKillTool.java` |
| `web/service/tool/BrowserActionTool.java` | `tool/impl/browser/BrowserActionTool.java` |
| `web/service/tool/BrowserScreenshotTool.java` | `tool/impl/browser/BrowserScreenshotTool.java` |
| `web/service/tool/BrowserInfoTool.java` | `tool/impl/browser/BrowserInfoTool.java` |
| `web/service/tool/SkillListTool.java` | `tool/impl/skill/SkillListTool.java` |
| `web/service/tool/SkillActivateTool.java` | `tool/impl/skill/SkillActivateTool.java` |
| `web/service/tool/SkillReferenceTool.java` | `tool/impl/skill/SkillReferenceTool.java` |
| `web/service/tool/KnowledgeSearchTool.java` | `tool/impl/knowledge/KnowledgeSearchTool.java` |
| `web/service/tool/RequestSandboxTool.java` | `tool/impl/sandbox/RequestSandboxTool.java` |

### 3.5 llm 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/LlmService.java` | `llm/LlmService.java` |
| `web/service/impl/BaseLlmServiceImpl.java` | `llm/impl/BaseLlmServiceImpl.java` |
| `web/service/impl/DeepSeekLlmServiceImpl.java` | `llm/impl/DeepSeekLlmServiceImpl.java` |
| `web/service/impl/ZhipuLlmServiceImpl.java` | `llm/impl/ZhipuLlmServiceImpl.java` |
| `web/model/llm/LlmRequest.java` | `llm/model/LlmRequest.java` |
| `web/model/llm/LlmResponse.java` | `llm/model/LlmResponse.java` |
| `web/model/llm/LlmMessage.java` | `llm/model/LlmMessage.java` |
| `web/model/llm/LlmChoice.java` | `llm/model/LlmChoice.java` |
| `web/model/llm/LlmCompletionResponse.java` | `llm/model/LlmCompletionResponse.java` |
| `web/model/llm/LlmStreamChunk.java` | `llm/model/LlmStreamChunk.java` |
| `web/model/llm/LlmToolCall.java` | `llm/model/LlmToolCall.java` |
| `web/model/llm/LlmToolResult.java` | `llm/model/LlmToolResult.java` |

### 3.6 knowledge 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/KnowledgeService.java` | `knowledge/service/KnowledgeService.java` |
| `web/service/EmbeddingService.java` | `knowledge/service/EmbeddingService.java` |
| `web/service/VectorStoreService.java` | `knowledge/service/VectorStoreService.java` |
| `web/service/DocumentParserService.java` | `knowledge/service/DocumentParserService.java` |
| `web/service/TextSplitterService.java` | `knowledge/service/TextSplitterService.java` |
| `web/service/impl/KnowledgeServiceImpl.java` | `knowledge/service/impl/KnowledgeServiceImpl.java` |
| `web/service/impl/EmbeddingServiceImpl.java` | `knowledge/service/impl/EmbeddingServiceImpl.java` |
| `web/service/impl/VectorStoreServiceImpl.java` | `knowledge/service/impl/VectorStoreServiceImpl.java` |
| `web/service/impl/DocumentParserServiceImpl.java` | `knowledge/service/impl/DocumentParserServiceImpl.java` |
| `web/service/impl/TextSplitterServiceImpl.java` | `knowledge/service/impl/TextSplitterServiceImpl.java` |
| `web/repository/KnowledgeBaseRepository.java` | `knowledge/repository/KnowledgeBaseRepository.java` |
| `web/repository/KnowledgeChunkRepository.java` | `knowledge/repository/KnowledgeChunkRepository.java` |
| `web/repository/KnowledgeDocumentRepository.java` | `knowledge/repository/KnowledgeDocumentRepository.java` |
| `web/model/entity/KnowledgeBaseEntity.java` | `knowledge/entity/KnowledgeBaseEntity.java` |
| `web/model/entity/KnowledgeChunkEntity.java` | `knowledge/entity/KnowledgeChunkEntity.java` |
| `web/model/entity/KnowledgeDocumentEntity.java` | `knowledge/entity/KnowledgeDocumentEntity.java` |

### 3.7 skill 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/SkillService.java` | `skill/service/SkillService.java` |
| `web/service/impl/SkillServiceImpl.java` | `skill/service/impl/SkillServiceImpl.java` |
| `web/service/impl/FileSyncService.java` | `skill/service/impl/FileSyncService.java` |
| `web/model/entity/Skill.java` | `skill/model/Skill.java` |

### 3.8 user 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/UserService.java` | `user/service/UserService.java` |
| `web/repository/UserRepository.java` | `user/repository/UserRepository.java` |
| `web/model/entity/UserEntity.java` | `user/entity/UserEntity.java` |

### 3.9 conversation 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/ConversationService.java` | `conversation/service/ConversationService.java` |
| `web/service/impl/ConversationServiceImpl.java` | `conversation/service/impl/ConversationServiceImpl.java` |
| `web/repository/ConversationSessionRepository.java` | `conversation/repository/ConversationSessionRepository.java` |
| `web/repository/ChatMessageRepository.java` | `conversation/repository/ChatMessageRepository.java` |
| `web/repository/TokenUsageRepository.java` | `conversation/repository/TokenUsageRepository.java` |
| `web/repository/UserSandboxRepository.java` | `conversation/repository/UserSandboxRepository.java` |
| `web/model/entity/ConversationSessionEntity.java` | `conversation/entity/ConversationSessionEntity.java` |
| `web/model/entity/ConversationSession.java` | `conversation/entity/ConversationSession.java` |
| `web/model/entity/ChatMessageEntity.java` | `conversation/entity/ChatMessageEntity.java` |
| `web/model/entity/ChatMessage.java` | `conversation/entity/ChatMessage.java` |
| `web/model/entity/TokenUsageEntity.java` | `conversation/entity/TokenUsageEntity.java` |
| `web/model/entity/UserSandboxEntity.java` | `conversation/entity/UserSandboxEntity.java` |

### 3.10 app 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/AgentAppService.java` | `app/service/AgentAppService.java` |
| `web/service/impl/AgentAppServiceImpl.java` | `app/service/impl/AgentAppServiceImpl.java` |
| `web/repository/AgentAppRepository.java` | `app/repository/AgentAppRepository.java` |
| `web/model/entity/AgentAppEntity.java` | `app/entity/AgentAppEntity.java` |

### 3.11 storage 包

| 原路径 | 新路径 |
|--------|--------|
| `web/service/FileStorageService.java` | `storage/FileStorageService.java` |
| `web/service/impl/LocalFileStorageServiceImpl.java` | `storage/impl/LocalFileStorageServiceImpl.java` |
| `web/service/impl/OssFileStorageServiceImpl.java` | `storage/impl/OssFileStorageServiceImpl.java` |

### 3.12 api 包

| 原路径 | 新路径 |
|--------|--------|
| `web/controller/*.java` | `api/controller/*.java` |
| `web/model/request/*.java` | `api/dto/request/*.java` |
| `web/model/response/*.java` | `api/dto/response/*.java` |
| `web/model/sse/*.java` | `api/sse/*.java` |

### 3.13 测试代码

| 原路径 | 新路径 |
|--------|--------|
| `playwright/PlaywrightBaiduTest.java` | `src/test/java/.../playwright/PlaywrightBaiduTest.java` |

---

## 四、Lombok 改造清单

### 4.1 Entity 类（@Getter @Setter @NoArgsConstructor）

| 类 | 改造说明 |
|----|----------|
| `UserEntity` | 移除手写 getter/setter，保留 `regenerateToken()` 方法 |
| `ConversationSessionEntity` | 添加 @Entity + @Getter @Setter @NoArgsConstructor |
| `ChatMessageEntity` | 同上 |
| `TokenUsageEntity` | 同上 |
| `UserSandboxEntity` | 同上 |
| `AgentAppEntity` | 同上 |
| `KnowledgeBaseEntity` | 同上 |
| `KnowledgeChunkEntity` | 同上 |
| `KnowledgeDocumentEntity` | 同上 |

### 4.2 不可变对象（@Getter @AllArgsConstructor 或 @Builder）

| 类 | 改造说明 |
|----|----------|
| `ToolDefinition` | @Getter @AllArgsConstructor（final 字段） |
| `Skill` | @Getter @AllArgsConstructor（final 字段），保留业务方法 |
| `ChatMessage` | 保留工厂方法，添加 @Getter |
| `PlanResult` | @Getter @AllArgsConstructor |
| `ReactResult` | @Getter @AllArgsConstructor |
| `ExecutionResult` | @Getter @AllArgsConstructor |
| `ExecutionStatus` | 改为 enum |

### 4.3 DTO 类（@Data 或 @Getter @Setter @NoArgsConstructor @AllArgsConstructor）

| 类 | 改造说明 |
|----|----------|
| `ApiResponse` | @Getter @AllArgsConstructor（保留静态工厂方法） |
| `SessionResponse` | @Data |
| `AuthResponse` | @Data |
| `AgentAppResponse` | @Data |
| `DocumentResponse` | @Data |
| `KnowledgeBaseResponse` | @Data |
| `ChatRequest` | @Data |
| `ExecuteRequest` | @Data |
| `FileWriteRequest` | @Data |
| `AuthRequest` | @Data |
| `SearchRequest` | @Data |

### 4.4 LLM Model 类

| 类 | 改造说明 |
|----|----------|
| `LlmRequest` | @Data @Builder |
| `LlmResponse` | @Getter @AllArgsConstructor |
| `LlmMessage` | @Getter @AllArgsConstructor |
| `LlmChoice` | @Data |
| `LlmCompletionResponse` | @Data |
| `LlmStreamChunk` | @Getter @AllArgsConstructor |
| `LlmToolCall` | @Getter @AllArgsConstructor（record 也可） |
| `LlmToolResult` | @Getter @AllArgsConstructor |
| `LlmUsage` | 已是 record，无需改造 |
| `AgentResponse` | @Getter @AllArgsConstructor |
| `AgentStep` | @Getter @AllArgsConstructor |

### 4.5 异常类（@Getter @AllArgsConstructor）

| 类 | 改造说明 |
|----|----------|
| `SessionNotFoundException` | @Getter @AllArgsConstructor，保留 `getSessionId()` |
| `SkillNotFoundException` | @Getter @AllArgsConstructor，保留 `getSkillId()` |
| `UnauthorizedException` | @AllArgsConstructor |

### 4.6 Service 类（@Slf4j 替代手写 Logger）

| 类 | 改造说明 |
|----|----------|
| 所有 Service 实现类 | 将 `private static final Logger log = ...` 替换为 @Slf4j |
| `ReactAgent` | @Slf4j |
| `PlanAgent` | @Slf4j |
| `AioSandboxStore` | @Slf4j |

---

## 五、新增文件

### 5.1 Tool 抽象基类

```java
// tool/base/AbstractTool.java
package com.example.sandbox.tool.base;

import com.example.sandbox.tool.ToolDefinition;
import com.example.sandbox.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 抽象基类 — 简化工具定义创建
 */
public abstract class AbstractTool implements Tool {

    protected final String name;
    protected final String description;
    protected final String sandboxType;

    protected AbstractTool(String name, String description, String sandboxType) {
        this.name = name;
        this.description = description;
        this.sandboxType = sandboxType;
    }

    protected AbstractTool(String name, String description) {
        this(name, description, "ALL");
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(name, description, buildParameters(), sandboxType);
    }

    /**
     * 子类实现：构建参数 schema
     */
    protected abstract Map<String, Object> buildParameters();

    /**
     * 快捷方法：创建字符串参数
     */
    protected Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * 快捷方法：构建参数 map
     */
    protected Map<String, Object> params(Map<String, Object> properties, String... required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", List.of(required));
        return params;
    }
}
```

### 5.2 沙箱类型枚举

```java
// common/enums/SandboxType.java
package com.example.sandbox.common.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum SandboxType {
    ALL("ALL", "所有沙箱可用"),
    AIO("AIO", "仅 AIO 沙箱可用"),
    COMMON("COMMON", "仅普通沙箱可用");

    private final String code;
    private final String description;
}
```

---

## 六、执行步骤

### Phase 1: 准备工作
- [ ] 创建新目录结构
- [ ] 创建新增文件（AbstractTool、SandboxType 枚举）
- [ ] 移动测试代码到 src/test/java

### Phase 2: 文件迁移
- [ ] 迁移 common 包文件
- [ ] 迁移 sandbox 包文件
- [ ] 迁移 agent 包文件
- [ ] 迁移 tool 包文件
- [ ] 迁移 llm 包文件
- [ ] 迁移 knowledge 包文件
- [ ] 迁移 skill 包文件
- [ ] 迁移 user 包文件
- [ ] 迁移 conversation 包文件
- [ ] 迁移 app 包文件
- [ ] 迁移 storage 包文件
- [ ] 迁移 api 包文件

### Phase 3: Lombok 改造
- [ ] Entity 类添加 Lombok 注解
- [ ] DTO 类添加 Lombok 注解
- [ ] Model 类添加 Lombok 注解
- [ ] Service 类添加 @Slf4j
- [ ] 移除手写 getter/setter/logger

### Phase 4: 修复引用
- [ ] 更新所有 import 语句
- [ ] 修复 Controller 对实现类的直接依赖
- [ ] 验证 Spring 组件扫描配置

### Phase 5: 验证
- [ ] 编译通过（mvn compile）
- [ ] 单元测试通过（mvn test）
- [ ] 启动成功
- [ ] 功能验证

---

## 七、注意事项

1. **Entity 类不要用 @Data** - @Data 会生成 equals/hashCode，可能导致 JPA 问题，用 @Getter @Setter @NoArgsConstructor

2. **保留业务方法** - 像 Skill.getContent()、UserEntity.regenerateToken() 这种业务方法要保留

3. **Controller 依赖注入修复** - 需要的方法要加到接口中，不要直接注入实现类

4. **分批执行** - 建议按 Phase 顺序执行，每个 Phase 完成后验证编译

5. **IDE 重构工具** - 可以用 IDEA 的 Refactor -> Move Class 功能自动更新引用

---

## 八、后续优化（可选）

完成以上重构后，可以考虑：

1. **方法拆分** - AgentServiceImpl.chat() 拆分为多个私有方法
2. **重复代码提取** - chat() 和 chatStream() 的公共逻辑抽取
3. **Builder 模式** - ReactAgent 构造函数改为 Builder
4. **接口隔离** - AgentService 拆分为 Query 和 Command 接口

---

## 九、当前进度（2024-06-04）

### 已完成

1. ✅ 创建新目录结构
2. ✅ 创建 AbstractTool 基类、SandboxType 枚举
3. ✅ 迁移 common 包（异常、配置、上下文）
4. ✅ 迁移 sandbox 包（SandboxAgent、AioSandboxClient、PlanAgent、ReactAgent 等）
5. ✅ 迁移 tool 包（Tool 接口、ToolDefinition、所有 Tool 实现类）
6. ✅ 迁移 llm 包（LlmService、模型类、实现类）
7. ✅ 迁移 skill、user、conversation 等 domain 包
8. ✅ 迁移 api 包（Controller、DTO、SSE）
9. ✅ 迁移启动类

### 待完成

1. ⏳ **修复 import 引用** - 部分文件存在 import 引用问题
2. ⏳ **删除旧目录** - 删除 `src/main/java/com/example/sandbox/web/` 等旧目录
3. ⏳ **编译验证** - 确保所有文件编译通过
4. ⏳ **Lombok 改造** - 添加 Lombok 注解，删除样板代码
5. ⏳ **Skill 读取改为走沙箱** - 当前 `Skill.getContent()` 和 `getReferenceFile()` 直接读宿主机文件，应改为走 `SandboxClient.readFile()` 读取沙箱内已同步的文件（路径: `/home/gem/skills/{skillId}/...`）
6. ⏳ **镜像支持旧 .doc 格式** - 当前 `/v1/util/convert_to_markdown` 只支持 `.docx`，不支持旧 `.doc` 格式。需要：
   - 镜像安装 `antiword` 或 `catdoc`：`apt-get install -y antiword catdoc`
   - 修改 `/v1/util/convert_to_markdown` 接口，对 `.doc` 文件用 `antiword` 或 `pandoc -f doc -t markdown` 转换
   - 或在 `DocumentParserTool` 代码层加 `.doc` 处理逻辑（备选方案）

### 功能优化（后续实现）

1. ⏳ **用户自定义 LLM 配置** - 支持用户接入自己的 API Key，使用不同模型

   **安全方案**：
   - 前端：Web Crypto API (AES-256-GCM) 加密 API Key 后传输
   - 后端：KMS 二次加密后存储，双重保护
   - 内存：明文 Key 用完即销毁，不记录日志
   - 传输：HTTPS + HSTS

   **多厂商适配**：
   - OpenAI 兼容协议（DeepSeek、智谱、Moonshot、Ollama）：现有 `BaseLlmServiceImpl` 直接支持
   - 非兼容协议需要写 Adapter：
     - `ClaudeAdapter` - Anthropic 协议
     - `QwenAdapter` - 阿里云通义
     - `ErnieAdapter` - 百度文心（需 access_token 换取）

   **新增内容**：
   - 数据库表 `user_llm_config`：存储加密后的用户 API 配置
   - `LlmAdapter` 接口：统一抽象不同厂商 API
   - 前端配置页面：用户管理自己的 LLM 配置

   **不引入 LangChain**：现有代码已覆盖核心能力，自己写 Adapter 更轻量可控

### 下一步操作建议

```bash
# 1. 在 IDEA 中打开项目，使用 Refactor 功能修复 import

# 2. 或者手动删除旧目录后让 IDE 自动修复
rm -rf src/main/java/com/example/sandbox/web/
rm -rf src/main/java/com/example/sandbox/agent/
rm -rf src/main/java/com/example/sandbox/aio/
rm -rf src/main/java/com/example/sandbox/playwright/

# 3. 编译验证
mvn compile

# 4. 运行测试
mvn test
```

### 注意事项

- 新文件已创建，旧文件未删除，便于对比和回滚
- 部分复杂 Service 文件（如 AgentServiceImpl、ConversationServiceImpl）可能需要手动修复 import
- Tool 实现类暂放在 `tool/impl/file/` 下，需要按功能分类移动
