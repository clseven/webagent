# Sandbox State Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent 增加一组克制、只读、实时查询的 Sandbox 状态工具，并在新会话首轮注入极小的当前目录与最近文件摘要。

**Architecture:** 不建立状态数据库、不做后台扫描，也不把完整 Sandbox 状态塞进 prompt。AIO Sandbox 是唯一事实源；`SandboxStateService` 统一执行受约束的实时查询和结果裁剪，七个 Tool 只负责参数定义与输出。`AgentServiceImpl` 仅在会话没有历史消息时请求一次轻量快照，查询失败不阻断对话。

**Tech Stack:** Java 17、Spring Boot 3.2、JUnit 5、Mockito、AIO Sandbox REST API、Maven。

---

## 范围与约束

本计划只实现 Sandbox 状态记忆，不包含 `preferences.md`。

新增工具：

| 工具 | 默认行为 | 数据来源 |
|---|---|---|
| `list_files(path, depth=2)` | 查看有限深度目录树 | `POST /v1/file/list` |
| `find_recent_files(days=3)` | 查找最近修改的用户文件 | `POST /v1/file/list`，按 `modified` 降序 |
| `search_files(pattern)` | 按文件名 glob 查找 | `POST /v1/file/find` |
| `get_cwd()` | 查询当前 Shell 工作目录 | 固定命令 `pwd` |
| `list_processes()` | 查询当前运行进程 | 固定命令 `ps` |
| `check_command(cmd)` | 判断命令是否可用 | 参数校验后执行 `command -v` |
| `get_env(key, safe=true)` | 查询单个环境变量并默认脱敏 | 参数校验后执行 `printenv` |

明确不做：

- 不保存目录快照、进程快照或环境变量副本。
- 不引入定时任务、消息队列、向量库或新的数据库表。
- 不自动注入完整目录树、进程列表或环境变量。
- 不允许工具执行调用者提供的任意 Shell 片段。
- 不改动现有 L1 滑动窗口和摘要逻辑。
- 不删除现有 Skill 系统；是否精简 Skill 属于独立任务。

## 文件结构

### 新增

- `src/main/java/com/example/sandbox/web/service/SandboxStateService.java`
  - 定义实时状态查询和首轮摘要接口。
- `src/main/java/com/example/sandbox/web/service/impl/SandboxStateServiceImpl.java`
  - 调用 AIO File/Shell API、裁剪输出、过滤隐藏运行时目录、处理脱敏。
- `src/main/java/com/example/sandbox/web/model/sandbox/SandboxFileState.java`
  - 表示文件路径、类型、大小和修改时间。
- `src/main/java/com/example/sandbox/web/service/tool/FindRecentFilesTool.java`
- `src/main/java/com/example/sandbox/web/service/tool/SearchFilesTool.java`
- `src/main/java/com/example/sandbox/web/service/tool/GetCwdTool.java`
- `src/main/java/com/example/sandbox/web/service/tool/ListProcessesTool.java`
- `src/main/java/com/example/sandbox/web/service/tool/CheckCommandTool.java`
- `src/main/java/com/example/sandbox/web/service/tool/GetEnvTool.java`
- `src/test/java/com/example/sandbox/web/service/impl/SandboxStateServiceImplTest.java`
- `src/test/java/com/example/sandbox/web/service/tool/SandboxStateToolsTest.java`
- `src/test/java/com/example/sandbox/web/service/impl/AgentSandboxOpeningContextTest.java`

### 修改

- `src/main/java/com/example/sandbox/aio/file/AioFileApi.java`
  - 增加 `/v1/file/find` 封装。
- `src/main/java/com/example/sandbox/aio/shell/AioShellApi.java`
  - 增加 `/v1/shell/sessions` 的只读列表封装。
- `src/main/java/com/example/sandbox/web/service/tool/ListFilesTool.java`
  - 将 `recursive` 参数替换为更直观的 `depth`，委托状态服务处理。
- `src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java`
  - 同步与流式路径仅在首轮注入极小 Sandbox 摘要。
- `docs/sandbox-api/integration-map.md`
  - 记录新增 AIO 封装。
- `docs/project-config-reference.md`
  - 更新工具目录和状态查询限制。

所有新增或修改的注释、Javadoc、测试说明和脚本注释必须使用中文。

---

### Task 1: 补齐 AIO 只读查询接口

**Files:**
- Modify: `src/main/java/com/example/sandbox/aio/file/AioFileApi.java`
- Modify: `src/main/java/com/example/sandbox/aio/shell/AioShellApi.java`
- Test: `src/test/java/com/example/sandbox/aio/AioStateApiContractTest.java`

- [ ] **Step 1: 编写 `/v1/file/find` 和 Shell 会话列表的失败测试**

沿用现有 `AioShellApiReadinessTest` 的方式 mock `AioHttpClient`，不增加新的测试依赖：

```java
@Test
void findFilesUsesAuthoritativeOpenApiFields() {
    AioHttpClient http = mock(AioHttpClient.class);
    Map<String, Object> response = Map.of(
            "success", true,
            "data", Map.of("files", List.of("/home/gem/workspace/analyze.py")));
    when(http.postMap("/v1/file/find", Map.of(
            "path", "/home/gem",
            "glob", "*.py"))).thenReturn(response);

    AioFileApi api = new AioFileApi(http);

    assertThat(api.find("/home/gem", "*.py")).isSameAs(response);
    verify(http).postMap("/v1/file/find", Map.of(
            "path", "/home/gem",
            "glob", "*.py"));
}

@Test
void listShellSessionsUsesReadOnlyEndpoint() {
    AioHttpClient http = mock(AioHttpClient.class);
    Map<String, Object> response = Map.of(
            "success", true,
            "data", Map.of("sessions", List.of()));
    when(http.getMap("/v1/shell/sessions")).thenReturn(response);

    AioShellApi api = new AioShellApi(http);

    assertThat(api.listSessions()).isSameAs(response);
    verify(http).getMap("/v1/shell/sessions");
}
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run:

```powershell
mvn -Dtest=AioStateApiContractTest test
```

Expected: FAIL，原因是 `AioFileApi.find` 和 `AioShellApi.listSessions` 尚不存在。

- [ ] **Step 3: 实现最小 AIO 封装**

在 `AioFileApi` 增加：

```java
/**
 * 按文件名 glob 模式查找文件。
 *
 * @param path 搜索根目录
 * @param glob 文件名 glob 模式
 * @return AIO 完整响应
 */
public Map<String, Object> find(String path, String glob) {
    return http.postMap("/v1/file/find", Map.of(
            "path", path,
            "glob", glob
    ));
}
```

在 `AioShellApi` 增加：

```java
/**
 * 查询当前活动 Shell 会话。
 *
 * @return AIO 完整响应
 */
public Map<String, Object> listSessions() {
    return http.getMap("/v1/shell/sessions");
}
```

- [ ] **Step 4: 运行契约测试**

Run:

```powershell
mvn -Dtest=AioStateApiContractTest test
```

Expected: PASS。

- [ ] **Step 5: 提交本任务**

```powershell
git add src/main/java/com/example/sandbox/aio/file/AioFileApi.java src/main/java/com/example/sandbox/aio/shell/AioShellApi.java src/test/java/com/example/sandbox/aio/AioStateApiContractTest.java
git commit -m "feat: add aio sandbox state query APIs"
```

---

### Task 2: 建立统一的实时 Sandbox 状态服务

**Files:**
- Create: `src/main/java/com/example/sandbox/web/service/SandboxStateService.java`
- Create: `src/main/java/com/example/sandbox/web/service/impl/SandboxStateServiceImpl.java`
- Create: `src/main/java/com/example/sandbox/web/model/sandbox/SandboxFileState.java`
- Create: `src/test/java/com/example/sandbox/web/service/impl/SandboxStateServiceImplTest.java`

- [ ] **Step 1: 编写文件状态查询失败测试**

覆盖以下行为：

```java
@Test
void listFilesDefaultsToWorkspaceAndDepthTwo() {
    when(files.list("/home/gem/workspace", true, false, 2, true, "name", false))
            .thenReturn(fileListResponse());

    String result = service.listFiles("session-1", null, null);

    assertThat(result).contains("/home/gem/workspace");
    verify(files).list("/home/gem/workspace", true, false, 2, true, "name", false);
}

@Test
void listFilesRejectsDepthOutsideOneToFive() {
    assertThat(service.listFiles("session-1", "/home/gem", 6))
            .contains("depth 必须在 1 到 5 之间");
    verifyNoInteractions(files);
}

@Test
void recentFilesFiltersDirectoriesHiddenRuntimeAndOldFiles() {
    when(files.list("/home/gem", true, false, 8, true, "modified", true))
            .thenReturn(mixedRecentFileResponse());

    String result = service.findRecentFiles("session-1", 3);

    assertThat(result)
            .contains("/home/gem/workspace/analyze.py")
            .doesNotContain("/home/gem/.runtime/")
            .doesNotContain("/home/gem/temp/previews/")
            .doesNotContain("old.csv");
}

@Test
void searchFilesUsesGlobAndLimitsOutput() {
    when(files.find("/home/gem", "*analyze*"))
            .thenReturn(findResponseWithMoreThanOneHundredFiles());

    String result = service.searchFiles("session-1", "analyze", null);

    assertThat(result).contains("仅显示前 100 项");
    verify(files).find("/home/gem", "*analyze*");
}
```

规则：

- `listFiles` 默认 `/home/gem/workspace`、深度 2。
- `depth` 允许 1 到 5。
- 单次最多返回 200 项，超出时明确提示截断。
- `findRecentFiles` 的 `days` 允许 1 到 30，默认 3，最多返回 20 项。
- 最近文件默认扫描 `/home/gem`，排除目录、点目录、`temp/previews` 和 `.runtime`。
- `searchFiles` 默认根目录 `/home/gem`，把普通名称转换为 `*名称*`；用户显式传入 `*`、`?`、`[]` 时按原 glob 使用。
- `searchFiles` 最多返回 100 项。

- [ ] **Step 2: 编写环境状态和脱敏失败测试**

```java
@Test
void getCwdReturnsCurrentReusableShellDirectory() {
    when(shell.exec("pwd")).thenReturn(shellSuccess("/home/gem/workspace\n"));

    assertThat(service.getCwd("session-1"))
            .isEqualTo("/home/gem/workspace");
}

@Test
void checkCommandRejectsShellSyntax() {
    assertThat(service.checkCommand("session-1", "python3; rm -rf /"))
            .contains("命令名格式不合法");
    verifyNoInteractions(shell);
}

@Test
void sensitiveEnvironmentVariableIsAlwaysMasked() {
    when(shell.exec("printenv 'OPENAI_API_KEY'"))
            .thenReturn(shellSuccess("sk-secret-value\n"));

    assertThat(service.getEnv("session-1", "OPENAI_API_KEY", false))
            .isEqualTo("OPENAI_API_KEY=sk-************alue");
}

@Test
void missingEnvironmentVariableIsReportedWithoutFailure() {
    when(shell.exec("printenv 'NOT_DEFINED'"))
            .thenReturn(shellFailure(""));

    assertThat(service.getEnv("session-1", "NOT_DEFINED", true))
            .contains("未设置");
}
```

安全规则：

- `check_command` 只接受 `^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$`。
- `get_env` 只接受 `^[A-Za-z_][A-Za-z0-9_]{0,127}$`。
- 环境变量名包含 `KEY`、`TOKEN`、`SECRET`、`PASSWORD`、`PASSWD`、`CREDENTIAL`、`COOKIE`、`AUTH` 时，无论 `safe` 值如何都必须脱敏。
- `safe=true` 时，非敏感值也只显示前后各 4 个字符；长度不超过 8 时全部替换为 `*`。
- `safe=false` 只允许完整显示非敏感变量。
- 输出值最多 500 个字符。
- `listProcesses` 使用固定命令，不接收参数，最多显示 50 行。

- [ ] **Step 3: 运行测试并确认按预期失败**

Run:

```powershell
mvn -Dtest=SandboxStateServiceImplTest test
```

Expected: FAIL，原因是状态服务和模型尚不存在。

- [ ] **Step 4: 创建接口和文件状态模型**

接口应保持小而直接：

```java
public interface SandboxStateService {

    String listFiles(String sessionId, String path, Integer depth);

    String findRecentFiles(String sessionId, Integer days);

    String searchFiles(String sessionId, String pattern, String path);

    String getCwd(String sessionId);

    String listProcesses(String sessionId);

    String checkCommand(String sessionId, String command);

    String getEnv(String sessionId, String key, Boolean safe);

    String buildOpeningContext(String sessionId);
}
```

`SandboxFileState` 使用不可变 record：

```java
/**
 * 描述 Sandbox 中一个文件的只读状态。
 *
 * @param path 文件绝对路径
 * @param directory 是否为目录
 * @param size 文件字节数，目录或未知时为空
 * @param modifiedAt 最后修改时间，未知时为空
 */
public record SandboxFileState(
        String path,
        boolean directory,
        Long size,
        Instant modifiedAt
) {
}
```

- [ ] **Step 5: 实现最小状态服务**

实现要求：

- 通过 `SandboxClientFactory.getAioClient(sessionId)` 获取同一用户 Sandbox。
- 仅调用固定 AIO API 或固定 Shell 命令。
- 解析 AIO 响应时兼容 `files` 为对象列表或字符串列表。
- 修改时间兼容 ISO-8601 字符串和 Unix 秒/毫秒数值。
- 查询失败返回简短中文错误字符串，不抛出到 ReAct 循环。
- 不对只读查询做自动重试：查询结果具有时效性，失败后由 Agent 决定是否再次调用。
- 不记录环境变量值到日志，只记录变量名和查询结果类型。
- 所有返回结果包含绝对路径，避免 Agent 猜测当前目录。

- [ ] **Step 6: 运行状态服务测试**

Run:

```powershell
mvn -Dtest=SandboxStateServiceImplTest test
```

Expected: PASS。

- [ ] **Step 7: 提交本任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/SandboxStateService.java src/main/java/com/example/sandbox/web/service/impl/SandboxStateServiceImpl.java src/main/java/com/example/sandbox/web/model/sandbox/SandboxFileState.java src/test/java/com/example/sandbox/web/service/impl/SandboxStateServiceImplTest.java
git commit -m "feat: add live sandbox state service"
```

---

### Task 3: 注册七个极简状态工具

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/tool/ListFilesTool.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/FindRecentFilesTool.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/SearchFilesTool.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/GetCwdTool.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/ListProcessesTool.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/CheckCommandTool.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/GetEnvTool.java`
- Create: `src/test/java/com/example/sandbox/web/service/tool/SandboxStateToolsTest.java`

- [ ] **Step 1: 编写工具定义和委托失败测试**

```java
@Test
void exposesClaudeStyleStateToolNames() {
    assertThat(tools.stream()
            .map(tool -> tool.getDefinition().getName()))
            .containsExactlyInAnyOrder(
                    "list_files",
                    "find_recent_files",
                    "search_files",
                    "get_cwd",
                    "list_processes",
                    "check_command",
                    "get_env");
}

@Test
void listFilesUsesDepthParameter() {
    String result = listFilesTool.execute("session-1", Map.of(
            "path", "/home/gem/workspace",
            "depth", 2
    ));

    verify(stateService).listFiles("session-1", "/home/gem/workspace", 2);
    assertThat(result).isEqualTo("tree");
}

@Test
void getEnvDefaultsSafeToTrue() {
    getEnvTool.execute("session-1", Map.of("key", "PATH"));

    verify(stateService).getEnv("session-1", "PATH", true);
}
```

同时断言所有七个工具的 `sandboxType` 为 `AIO`，参数 schema 与本计划一致。

- [ ] **Step 2: 运行测试并确认按预期失败**

Run:

```powershell
mvn -Dtest=SandboxStateToolsTest test
```

Expected: FAIL，原因是六个新工具不存在，`list_files` 仍使用 `recursive`。

- [ ] **Step 3: 实现七个薄 Tool**

每个 Tool：

- 使用构造器注入 `SandboxStateService`。
- `getDefinition()` 只暴露必要参数。
- `execute()` 只做参数取值和默认值转换，然后委托服务。
- 描述明确告诉模型：遇到“刚才生成的文件在哪里”“当前装了什么”“命令是否可用”等问题，应先调用工具，不应根据历史猜测。

参数 schema：

```text
list_files(path?: string, depth?: integer=2)
find_recent_files(days?: integer=3)
search_files(pattern: string, path?: string="/home/gem")
get_cwd()
list_processes()
check_command(cmd: string)
get_env(key: string, safe?: boolean=true)
```

- [ ] **Step 4: 运行工具测试**

Run:

```powershell
mvn -Dtest=SandboxStateToolsTest test
```

Expected: PASS。

- [ ] **Step 5: 提交本任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/tool src/test/java/com/example/sandbox/web/service/tool/SandboxStateToolsTest.java
git commit -m "feat: expose sandbox state tools"
```

---

### Task 4: 只在新会话首轮注入极小开场上下文

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/AgentSandboxOpeningContextTest.java`

- [ ] **Step 1: 编写同步与流式首轮判定失败测试**

```java
@Test
void firstTurnAppendsSmallSandboxContext() {
    when(conversationService.getRecentHistory("session-1", 20))
            .thenReturn(List.of());
    when(stateService.buildOpeningContext("session-1"))
            .thenReturn("""
                    当前沙箱:
                      cwd: /home/gem/workspace
                      最近修改: analyze.py（10 分钟前）
                    """);

    service.chat("session-1", "分析脚本在哪");

    verify(stateService).buildOpeningContext("session-1");
    assertThat(capturedExecutorPrompt()).contains("当前沙箱:");
}

@Test
void laterTurnsDoNotInjectOpeningContextAgain() {
    when(conversationService.getRecentHistory("session-1", 20))
            .thenReturn(List.of(ChatMessage.userMessage("之前的消息")));

    service.chat("session-1", "继续");

    verifyNoInteractions(stateService);
    assertThat(capturedExecutorPrompt()).doesNotContain("当前沙箱:");
}

@Test
void openingContextFailureDoesNotBlockChat() {
    when(conversationService.getRecentHistory("session-1", 20))
            .thenReturn(List.of());
    when(stateService.buildOpeningContext("session-1"))
            .thenReturn("");

    assertThatCode(() -> service.chat("session-1", "你好"))
            .doesNotThrowAnyException();
}
```

为 `chatStream` 增加同样的首轮与非首轮测试，避免同步和流式行为分叉。

- [ ] **Step 2: 运行测试并确认按预期失败**

Run:

```powershell
mvn -Dtest=AgentSandboxOpeningContextTest test
```

Expected: FAIL，原因是 `AgentServiceImpl` 尚未注入 `SandboxStateService`。

- [ ] **Step 3: 实现首轮极小快照**

`SandboxStateServiceImpl.buildOpeningContext`：

- 调用 `getCwd`。
- 查询最近 24 小时文件，只取前 2 项。
- 仅输出相对 `/home/gem/` 的短路径和人类可读时间。
- 总长度上限 300 字符。
- 没有任何可用状态时返回空字符串。
- 任一子查询失败时保留其他成功字段，不抛异常。

输出格式固定：

```text
---
当前沙箱:
  cwd: /home/gem/workspace
  最近修改: workspace/analyze.py（10 分钟前）, uploads/data.csv（1 小时前）
---
```

`AgentServiceImpl`：

- 在保存当前用户消息之前已经取得 `history`，以 `history.isEmpty()` 判定首轮。
- 仅首轮调用 `buildOpeningContext(sessionId)`。
- 将快照追加到原 system prompt 末尾，不放到用户消息中。
- 同步和流式路径共用一个私有方法，避免逻辑复制。
- 快照失败只写 debug/warn 日志，不阻断规划器或执行器。

- [ ] **Step 4: 运行首轮上下文测试**

Run:

```powershell
mvn -Dtest=AgentSandboxOpeningContextTest test
```

Expected: PASS。

- [ ] **Step 5: 提交本任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java src/test/java/com/example/sandbox/web/service/impl/AgentSandboxOpeningContextTest.java
git commit -m "feat: add minimal sandbox opening context"
```

---

### Task 5: 集成验证与核心验收场景

**Files:**
- Test: `src/test/java/com/example/sandbox/web/service/impl/SandboxStateAcceptanceTest.java`

- [ ] **Step 1: 编写“失忆恢复”验收测试**

```java
@Test
void findsRecentlyCreatedAnalysisScriptWithoutConversationMemory() {
    when(files.list("/home/gem", true, false, 8, true, "modified", true))
            .thenReturn(responseContainingRecent(
                    "/home/gem/workspace/analyze.py",
                    Instant.now().minusSeconds(600)));

    String observation = stateService.findRecentFiles("new-session", 3);

    assertThat(observation)
            .contains("/home/gem/workspace/analyze.py")
            .contains("10 分钟");
}
```

- [ ] **Step 2: 编写安全与克制验收测试**

验收以下边界：

- 不调用任何状态工具时，不会把目录树、进程或环境变量注入 prompt。
- 首轮 prompt 只含 cwd 和最多两个最近文件。
- 第二轮不重复注入首轮快照。
- `get_env("OPENAI_API_KEY", false)` 仍不返回原值。
- `check_command("python3;cat /etc/passwd")` 不执行 Shell。
- 工具错误作为 observation 返回，ReAct 可继续执行。

- [ ] **Step 3: 运行状态相关测试**

Run:

```powershell
mvn -Dtest=AioStateApiContractTest,SandboxStateServiceImplTest,SandboxStateToolsTest,AgentSandboxOpeningContextTest,SandboxStateAcceptanceTest test
```

Expected: 全部 PASS，无失败和错误。

- [ ] **Step 4: 运行完整测试套件**

Run:

```powershell
mvn test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 手工 Sandbox 验收**

在真实 AIO Sandbox 中依次验证：

```text
1. 在 /home/gem/workspace 创建 analyze.py。
2. 新建会话并问：“我刚才创建的分析脚本在哪？”
3. 观察 Agent 调用 find_recent_files，而不是猜测路径。
4. 运行一个持续进程，询问“现在有什么进程在跑？”
5. 观察 Agent 调用 list_processes。
6. 询问“python3 可用吗？”
7. 观察 Agent 调用 check_command。
8. 查询一个敏感环境变量，确认结果被脱敏。
```

通过标准：

- Agent 返回 `/home/gem/workspace/analyze.py` 的真实路径。
- 没有完整状态自动注入。
- 状态查询结果来自当前 Sandbox，而不是旧会话文本。
- 敏感环境变量不会进入模型 observation、日志或最终回答。

---

### Task 6: 更新契约文档和项目知识

**Files:**
- Modify: `docs/sandbox-api/integration-map.md`
- Modify: `docs/project-config-reference.md`
- Modify: affected pages under `D:\obsidian\llm-wiki\Projects\WebAgent Clean`

- [ ] **Step 1: 更新 AIO 集成映射**

记录：

```text
AioFileApi.find -> POST /v1/file/find
AioShellApi.listSessions -> GET /v1/shell/sessions
```

说明 `find_recent_files` 使用 `/v1/file/list` 的 `sort_by=modified`，不是新的 AIO endpoint。

- [ ] **Step 2: 更新工具目录和运行约束**

文档中增加七个工具的参数、默认值、输出上限和脱敏规则，并明确：

- Sandbox 是唯一状态源。
- 没有后台记忆同步。
- 只有首轮极小快照自动注入。

- [ ] **Step 3: 更新 Obsidian 项目知识**

至少更新：

- `20 模块/Agent 编排/Agent 编排模块.md`
- `20 模块/Agent 编排/计划与 ReAct 执行.md`
- `20 模块/Sandbox/AIO Sandbox 客户端.md`
- `20 模块/工具系统/工具目录.md`
- `20 模块/工具系统/文件与文档工具.md`
- `01 当前上下文.md`

- [ ] **Step 4: 校验知识库**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .agents/skills/maintain-project-knowledge/scripts/validate-knowledge-base.ps1
```

Expected: 校验通过。

- [ ] **Step 5: 提交文档**

```powershell
git add docs/sandbox-api/integration-map.md docs/project-config-reference.md
git commit -m "docs: document sandbox state memory"
```

---

## 实施顺序

1. 先完成 AIO 查询封装。
2. 再完成统一状态服务及安全边界。
3. 注册七个只读工具。
4. 最后接入首轮极小快照。
5. 通过自动化和真实 Sandbox 场景验收后，再考虑 `preferences.md`。

## 风险控制

- **AIO 修改时间字段差异：** 用测试覆盖 ISO-8601、Unix 秒、Unix 毫秒和缺失字段；缺失修改时间的文件不进入最近文件结果。
- **目录过大：** 所有列表有深度和条数上限；不返回无限递归结果。
- **Shell 注入：** 只有固定命令可执行；命令名和环境变量名先经过白名单正则。
- **密钥泄露：** 敏感变量名永远脱敏，日志不记录变量值。
- **首轮延迟：** 开场快照最多两次轻量查询，任何失败立即降级为空，不重试、不阻断对话。
- **现有未提交改动：** 实施时基于当前 AIO 拆分文件继续修改，不回滚用户已有工作。

## 自检结果

- 规格覆盖：七个状态工具、首轮小上下文、实时事实源、失败降级和验收场景均有对应任务。
- 完整性扫描：计划没有未定义任务或延后处理步骤。
- 类型一致性：工具参数、服务方法和测试调用保持一致。
- 范围检查：`preferences.md`、长期记忆、向量记忆和 Skill 精简均明确排除。
