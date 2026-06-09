# 用户工作空间持久化与 Office 预览实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将知识库文件和普通上传文件改为用户级持久化，在沙箱重建时自动恢复，并为知识库与普通文件分别接入 LibreOffice 转 PDF 预览。

**Architecture:** 本地文件系统是持久化主副本，AIO 沙箱是可重建工作副本。新增用户工作空间存储服务统一路径和文件名，文件同步服务负责二进制恢复，Office 预览服务只负责沙箱路径校验、缓存和转换；现有 `FilePreviewer` 继续共用，但通过 `source` 和 `previewType` 区分知识库双栏预览与普通单栏预览。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Data JPA、JUnit 5、Mockito、AIO Sandbox API、LibreOffice、Vue 3、Node.js 内置测试模块

---

## 文件结构

### 新增

- `src/main/java/com/example/sandbox/web/service/impl/UserWorkspaceStorageService.java`
  - 生成用户级本地路径和沙箱路径。
  - 清理文件名。
  - 二进制读写、替换、删除和重复检测。
- `src/main/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationService.java`
  - 将旧 `doc_{id}.{ext}` 文件迁移到新目录。
  - 成功后更新 `knowledge_document.storage_path`。
- `src/main/java/com/example/sandbox/web/service/impl/OfficePreviewService.java`
  - 判断 Office 类型。
  - 校验 `/home/gem` 路径。
  - 生成缓存键并调用 LibreOffice。
- `src/main/java/com/example/sandbox/web/model/response/FilePreviewContent.java`
  - 统一承载预览字节、媒体类型和渲染类型。
- `src/test/java/com/example/sandbox/web/service/impl/UserWorkspaceStorageServiceTest.java`
- `src/test/java/com/example/sandbox/web/service/impl/FileSyncServiceTest.java`
- `src/test/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationServiceTest.java`
- `src/test/java/com/example/sandbox/web/service/impl/OfficePreviewServiceTest.java`
- `src/test/java/com/example/sandbox/web/controller/FileUploadControllerTest.java`
- `src/test/java/com/example/sandbox/web/controller/PreviewControllerTest.java`
- `src/test/java/com/example/sandbox/web/service/impl/SandboxWorkspaceRestoreTest.java`
- `src/test/js/file-previewer-office.test.js`

### 修改

- `src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java`
- `src/main/java/com/example/sandbox/web/service/KnowledgeService.java`
- `src/main/java/com/example/sandbox/web/service/impl/FileSyncService.java`
- `src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java`
- `src/main/java/com/example/sandbox/web/controller/FileUploadController.java`
- `src/main/java/com/example/sandbox/web/controller/RagController.java`
- `src/main/java/com/example/sandbox/web/controller/SandboxController.java`
- `src/main/resources/static/js/components/FilePreviewer.js`
- `src/main/resources/static/js/api.js`
- `src/main/resources/application.yml`
- `docs/frontend-file-preview.md`

---

### Task 1: 建立用户级统一路径与二进制存储

**Files:**
- Create: `src/main/java/com/example/sandbox/web/service/impl/UserWorkspaceStorageService.java`
- Create: `src/test/java/com/example/sandbox/web/service/impl/UserWorkspaceStorageServiceTest.java`

- [ ] **Step 1: 编写路径和文件名测试**

```java
package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserWorkspaceStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsMatchingKnowledgePaths() {
        UserWorkspaceStorageService service = service();

        assertThat(service.knowledgeFile(7L, 13L, "合同.pdf"))
                .isEqualTo(tempDir.resolve("users/7/knowledge/13/合同.pdf"));
        assertThat(service.knowledgeSandboxPath(13L, "合同.pdf"))
                .isEqualTo("/home/gem/knowledge/13/合同.pdf");
    }

    @Test
    void buildsUserScopedUploadPaths() {
        UserWorkspaceStorageService service = service();

        assertThat(service.uploadFile(7L, "data.xlsx"))
                .isEqualTo(tempDir.resolve("users/7/uploads/data.xlsx"));
        assertThat(service.uploadSandboxPath("data.xlsx"))
                .isEqualTo("/home/gem/uploads/data.xlsx");
    }

    @Test
    void stripsPathSegmentsAndRejectsBlankNames() {
        UserWorkspaceStorageService service = service();

        assertThat(service.sanitizeFileName("../folder/report.pdf"))
                .isEqualTo("report.pdf");
        assertThat(service.sanitizeFileName("C:\\temp\\report.pdf"))
                .isEqualTo("report.pdf");
        assertThatThrownBy(() -> service.sanitizeFileName(".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UserWorkspaceStorageService service() {
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        return new UserWorkspaceStorageService(properties);
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -Dtest=UserWorkspaceStorageServiceTest test
```

Expected: 编译失败，提示 `UserWorkspaceStorageService` 不存在。

- [ ] **Step 3: 实现统一路径服务**

```java
@Service
public class UserWorkspaceStorageService {

    private static final String KNOWLEDGE_SANDBOX_ROOT = "/home/gem/knowledge";
    private static final String UPLOAD_SANDBOX_ROOT = "/home/gem/uploads";

    private final Path basePath;

    public UserWorkspaceStorageService(AgentConfigProperties properties) {
        this.basePath = Paths.get(properties.getStorage().getLocal().getBasePath())
                .toAbsolutePath()
                .normalize();
    }

    public Path knowledgeRoot(Long userId) {
        return userRoot(userId).resolve("knowledge");
    }

    public Path uploadRoot(Long userId) {
        return userRoot(userId).resolve("uploads");
    }

    public Path knowledgeFile(Long userId, Long kbId, String fileName) {
        return knowledgeRoot(userId)
                .resolve(String.valueOf(kbId))
                .resolve(sanitizeFileName(fileName))
                .normalize();
    }

    public Path uploadFile(Long userId, String fileName) {
        return uploadRoot(userId)
                .resolve(sanitizeFileName(fileName))
                .normalize();
    }

    public String knowledgeSandboxPath(Long kbId, String fileName) {
        return KNOWLEDGE_SANDBOX_ROOT + "/" + kbId + "/" + sanitizeFileName(fileName);
    }

    public String uploadSandboxPath(String fileName) {
        return UPLOAD_SANDBOX_ROOT + "/" + sanitizeFileName(fileName);
    }

    public Path write(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return target;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + target, e);
        }
    }

    public byte[] read(Path source) {
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败: " + source, e);
        }
    }

    public boolean exists(Path path) {
        return Files.isRegularFile(path);
    }

    public void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException("文件删除失败: " + path, e);
        }
    }

    public String sanitizeFileName(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalized = fileName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("非法文件名: " + fileName);
        }
        return name;
    }

    private Path userRoot(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return basePath.resolve("users").resolve(String.valueOf(userId));
    }
}
```

- [ ] **Step 4: 运行路径测试**

Run:

```powershell
mvn -Dtest=UserWorkspaceStorageServiceTest test
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/impl/UserWorkspaceStorageService.java src/test/java/com/example/sandbox/web/service/impl/UserWorkspaceStorageServiceTest.java
git commit -m "feat: add user workspace storage paths"
```

---

### Task 2: 将文件同步改为二进制并返回失败明细

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/FileSyncService.java`
- Create: `src/test/java/com/example/sandbox/web/service/impl/FileSyncServiceTest.java`

- [ ] **Step 1: 编写二进制同步测试**

```java
package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void syncsKnowledgeAndUploadsAsBytes() throws Exception {
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        UserWorkspaceStorageService storage = new UserWorkspaceStorageService(properties);
        byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46, 0x00, (byte) 0xFF};
        byte[] image = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        Files.createDirectories(storage.knowledgeFile(3L, 9L, "a.pdf").getParent());
        Files.write(storage.knowledgeFile(3L, 9L, "a.pdf"), pdf);
        Files.createDirectories(storage.uploadFile(3L, "b.png").getParent());
        Files.write(storage.uploadFile(3L, "b.png"), image);

        AioSandboxClient client = mock(AioSandboxClient.class);
        when(client.writeFile("/home/gem/knowledge/9/a.pdf", pdf)).thenReturn(true);
        when(client.writeFile("/home/gem/uploads/b.png", image)).thenReturn(true);

        FileSyncService service = new FileSyncService(storage);
        FileSyncService.SyncResult result = service.syncUserWorkspace(3L, client);

        assertThat(result.failedPaths()).isEmpty();
        assertThat(result.successCount()).isEqualTo(2);
        verify(client).writeFile("/home/gem/knowledge/9/a.pdf", pdf);
        verify(client).writeFile("/home/gem/uploads/b.png", image);
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -Dtest=FileSyncServiceTest test
```

Expected: 缺少 `syncUserWorkspace`、`SyncResult` 或对应构造器。

- [ ] **Step 3: 实现二进制目录同步**

将 `FileSyncService` 改为构造器注入，并加入：

```java
public record SyncResult(int successCount, List<String> failedPaths) {
    public boolean allSucceeded() {
        return failedPaths.isEmpty();
    }
}

public SyncResult syncUserWorkspace(Long userId, AioSandboxClient client) {
    List<String> failed = new ArrayList<>();
    int success = 0;
    success += syncTree(
            storage.knowledgeRoot(userId),
            "/home/gem/knowledge",
            client,
            failed
    );
    success += syncTree(
            storage.uploadRoot(userId),
            "/home/gem/uploads",
            client,
            failed
    );
    return new SyncResult(success, List.copyOf(failed));
}

private int syncTree(Path sourceRoot,
                     String sandboxRoot,
                     AioSandboxClient client,
                     List<String> failed) {
    if (!Files.exists(sourceRoot)) {
        return 0;
    }
    int success = 0;
    try (var stream = Files.walk(sourceRoot)) {
        for (Path source : stream.filter(Files::isRegularFile).toList()) {
            String relative = sourceRoot.relativize(source).toString().replace('\\', '/');
            String target = sandboxRoot + "/" + relative;
            try {
                byte[] bytes = Files.readAllBytes(source);
                if (client.writeFile(target, bytes)) {
                    success++;
                } else {
                    failed.add(target);
                }
            } catch (Exception e) {
                failed.add(target);
                log.warn("同步文件失败: {} -> {}", source, target, e);
            }
        }
    } catch (IOException e) {
        throw new RuntimeException("遍历用户工作空间失败: " + sourceRoot, e);
    }
    return success;
}
```

保留现有 `syncSkill`，但普通文件同步不再调用 `Files.readString()`。

- [ ] **Step 4: 运行同步测试**

Run:

```powershell
mvn -Dtest=FileSyncServiceTest test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/impl/FileSyncService.java src/test/java/com/example/sandbox/web/service/impl/FileSyncServiceTest.java
git commit -m "fix: sync user workspace files as binary"
```

---

### Task 3: 迁移知识库文件并统一知识库路径

**Files:**
- Create: `src/main/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationService.java`
- Create: `src/test/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationServiceTest.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java`
- Modify: `src/main/java/com/example/sandbox/web/service/KnowledgeService.java`
- Modify: `src/main/java/com/example/sandbox/web/repository/KnowledgeDocumentRepository.java`

- [ ] **Step 1: 增加按用户读取文档的稳定查询**

```java
List<KnowledgeDocumentEntity> findByUserIdOrderByIdAsc(Long userId);
```

- [ ] **Step 2: 编写旧文件迁移测试**

```java
@Test
void migratesLegacyFileBeforeUpdatingStoragePath() throws Exception {
    Path legacy = tempDir.resolve("knowledge/4/doc_12.pdf");
    Files.createDirectories(legacy.getParent());
    byte[] bytes = new byte[] {1, 2, 3, 4};
    Files.write(legacy, bytes);

    KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
    doc.setId(12L);
    doc.setUserId(4L);
    doc.setKbId(8L);
    doc.setFileName("说明书.pdf");
    doc.setStoragePath(legacy.toString());

    KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
    when(repository.save(doc)).thenReturn(doc);
    KnowledgeFileMigrationService service =
            new KnowledgeFileMigrationService(repository, storage);

    Path migrated = service.ensureCanonicalFile(doc);

    assertThat(migrated)
            .isEqualTo(tempDir.resolve("users/4/knowledge/8/说明书.pdf"));
    assertThat(Files.readAllBytes(migrated)).containsExactly(bytes);
    assertThat(doc.getStoragePath()).isEqualTo(migrated.toAbsolutePath().toString());
    verify(repository).save(doc);
}
```

- [ ] **Step 3: 运行迁移测试并确认失败**

Run:

```powershell
mvn -Dtest=KnowledgeFileMigrationServiceTest test
```

Expected: `KnowledgeFileMigrationService` 不存在。

- [ ] **Step 4: 实现可重试迁移**

核心方法：

```java
@Transactional
public Path ensureCanonicalFile(KnowledgeDocumentEntity document) {
    Path canonical = storage.knowledgeFile(
            document.getUserId(),
            document.getKbId(),
            document.getFileName()
    );
    if (Files.isRegularFile(canonical)) {
        updateStoragePath(document, canonical);
        return canonical;
    }

    Path source = Paths.get(document.getStoragePath()).toAbsolutePath().normalize();
    if (!Files.isRegularFile(source)) {
        throw new RuntimeException("知识库原文件不存在: " + source);
    }

    byte[] bytes = storage.read(source);
    storage.write(canonical, bytes);
    try {
        if (Files.size(canonical) != bytes.length) {
            throw new RuntimeException("迁移文件大小校验失败: " + canonical);
        }
    } catch (IOException e) {
        throw new RuntimeException("迁移文件校验失败: " + canonical, e);
    }

    updateStoragePath(document, canonical);
    if (!source.equals(canonical)) {
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            log.warn("旧知识库文件保留，稍后可清理: {}", source, e);
        }
    }
    return canonical;
}

private void updateStoragePath(KnowledgeDocumentEntity document, Path canonical) {
    String path = canonical.toAbsolutePath().toString();
    if (!path.equals(document.getStoragePath())) {
        document.setStoragePath(path);
        repository.save(document);
    }
}
```

- [ ] **Step 5: 修改知识库上传和沙箱路径**

在 `KnowledgeServiceImpl` 中：

```java
Path stored = workspaceStorage.write(
        workspaceStorage.knowledgeFile(userId, kbId, originalFileName),
        file.getBytes()
);
document.setStoragePath(stored.toAbsolutePath().toString());
```

所有沙箱路径统一改为：

```java
workspaceStorage.knowledgeSandboxPath(document.getKbId(), document.getFileName())
```

替换文件时，如果新文件名变化：

1. 删除旧的本地规范路径。
2. 写入新规范路径。
3. 更新 `fileName`、`fileType` 和 `storagePath`。
4. 删除旧沙箱路径。
5. 重新处理和同步。

- [ ] **Step 6: 增加用户全部知识库文件迁移入口**

在 `KnowledgeFileMigrationService` 增加：

```java
@Transactional
public List<Path> migrateUser(Long userId) {
    List<Path> migrated = new ArrayList<>();
    for (KnowledgeDocumentEntity document : repository.findByUserIdOrderByIdAsc(userId)) {
        try {
            migrated.add(ensureCanonicalFile(document));
        } catch (RuntimeException e) {
            log.warn("知识库文件迁移失败: userId={}, docId={}",
                    userId, document.getId(), e);
        }
    }
    return List.copyOf(migrated);
}
```

在迁移测试中补充 `migrateUser` 用例，验证文档按 ID 稳定处理，单个缺失源文件会记录失败并继续迁移其他文档；返回值只包含成功准备好的规范路径。

- [ ] **Step 7: 运行迁移与工作空间路径测试**

Run:

```powershell
mvn -Dtest=KnowledgeFileMigrationServiceTest,UserWorkspaceStorageServiceTest test
```

Expected: 所有测试通过。

- [ ] **Step 8: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/service src/main/java/com/example/sandbox/web/repository/KnowledgeDocumentRepository.java src/test/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationServiceTest.java
git commit -m "feat: migrate knowledge files to canonical user paths"
```

---

### Task 4: 将普通上传改为用户级持久化

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/controller/FileUploadController.java`
- Modify: `src/main/resources/static/js/api.js`
- Test: `src/test/java/com/example/sandbox/web/controller/FileUploadControllerTest.java`

- [ ] **Step 1: 编写本地优先上传测试**

使用 `@WebMvcTest(FileUploadController.class)`，模拟：

```java
when(agentService.getSession("s1")).thenReturn(sessionForUser(5L));
when(workspaceStorage.uploadFile(5L, "report.pdf"))
        .thenReturn(tempDir.resolve("users/5/uploads/report.pdf"));
when(workspaceStorage.exists(any(Path.class))).thenReturn(false);
when(sandboxService.isAioSandbox("s1")).thenReturn(true);
when(sandboxService.getAioClient("s1")).thenReturn(aioClient);
when(aioClient.writeFile("/home/gem/uploads/report.pdf", PDF_BYTES)).thenReturn(true);
```

断言：

```java
verify(workspaceStorage).write(any(Path.class), eq(PDF_BYTES));
verify(aioClient).writeFile("/home/gem/uploads/report.pdf", PDF_BYTES);
jsonPath("$.data").value("/home/gem/uploads/report.pdf");
```

再增加重复测试：当本地规范路径存在时返回 HTTP 409，并携带 `fileName`。

- [ ] **Step 2: 运行控制器测试并确认失败**

Run:

```powershell
mvn -Dtest=FileUploadControllerTest test
```

Expected: 当前控制器只写沙箱，验证本地写入失败。

- [ ] **Step 3: 修改普通上传顺序**

核心逻辑：

```java
ConversationSession session = agentService.getSession(sessionId);
Long userId = session.getUserId();
String fileName = workspaceStorage.sanitizeFileName(file.getOriginalFilename());
Path localPath = workspaceStorage.uploadFile(userId, fileName);

if (workspaceStorage.exists(localPath)) {
    throw new DuplicateFileException(fileName, null);
}

byte[] bytes = file.getBytes();
workspaceStorage.write(localPath, bytes);

String sandboxPath = workspaceStorage.uploadSandboxPath(fileName);
if (sandboxService.isAioSandbox(sessionId)) {
    AioSandboxClient client = sandboxService.getAioClient(sessionId);
    if (!client.writeFile(sandboxPath, bytes)) {
        log.warn("普通上传已持久化但沙箱同步失败: userId={}, path={}", userId, sandboxPath);
    }
}
return ApiResponse.success(sandboxPath);
```

- [ ] **Step 4: 修改替换接口**

替换操作使用同一个最终文件名：

```java
workspaceStorage.write(localPath, bytes);
boolean sandboxUpdated = client.writeFile(sandboxPath, bytes);
```

本地写入失败时返回失败；沙箱失败时保留本地文件，并返回带警告的成功结果或记录日志，
让新沙箱恢复时重试。

- [ ] **Step 5: 保持前端 DuplicateHandler 不变**

`api.uploadFile` 和 `api.replaceFile` 的 URL 与参数保持不变，因此
`DuplicateHandler` 的替换、保留两份、跳过流程无需新增分支。

- [ ] **Step 6: 运行控制器测试**

Run:

```powershell
mvn -Dtest=FileUploadControllerTest test
```

Expected: 上传、重复、替换测试全部通过。

- [ ] **Step 7: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/controller/FileUploadController.java src/test/java/com/example/sandbox/web/controller/FileUploadControllerTest.java src/main/resources/static/js/api.js
git commit -m "feat: persist ordinary uploads per user"
```

---

### Task 5: 新沙箱创建后恢复用户文件

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/FileSyncService.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationService.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/SandboxWorkspaceRestoreTest.java`

- [ ] **Step 1: 编写恢复顺序测试**

将“新沙箱初始化后的动作”提取为包级方法：

```java
void restoreUserWorkspace(Long userId, String sessionId, AioSandboxClient client)
```

测试验证调用顺序：

```java
InOrder order = inOrder(migrationService, fileSyncService);
order.verify(migrationService).migrateUser(userId);
order.verify(fileSyncService).syncUserWorkspace(userId, client);
```

并验证同步失败不会删除本地文件，也不会阻止技能同步。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -Dtest=SandboxWorkspaceRestoreTest test
```

Expected: `restoreUserWorkspace` 尚不存在。

- [ ] **Step 3: 接入沙箱创建流程**

在 `initAioDirectories` 完成后：

```java
AioSandboxClient client = new AioSandboxClient("http://" + endpoint);
restoreUserWorkspace(userId, sessionId, client);
syncAllEnabledSkills(sessionId);
```

实现：

```java
void restoreUserWorkspace(Long userId, String sessionId, AioSandboxClient client) {
    migrationService.migrateUser(userId);
    FileSyncService.SyncResult result = fileSyncService.syncUserWorkspace(userId, client);
    if (!result.allSucceeded()) {
        log.warn("用户工作空间恢复存在失败: userId={}, failed={}",
                userId, result.failedPaths());
    } else {
        log.info("用户工作空间恢复完成: userId={}, files={}",
                userId, result.successCount());
    }
}
```

- [ ] **Step 4: 只在创建新沙箱时执行全量恢复**

复用健康沙箱时不重复全量同步。预览发现单个文件缺失时由后续任务执行单文件恢复。

- [ ] **Step 5: 运行恢复测试和 Maven 编译**

Run:

```powershell
mvn -Dtest=SandboxWorkspaceRestoreTest,FileSyncServiceTest test
mvn -DskipTests compile
```

Expected: 测试和编译均成功。

- [ ] **Step 6: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java src/main/java/com/example/sandbox/web/service/impl/FileSyncService.java src/main/java/com/example/sandbox/web/service/impl/KnowledgeFileMigrationService.java src/test/java/com/example/sandbox/web/service/impl/SandboxWorkspaceRestoreTest.java
git commit -m "feat: restore user files when sandbox is recreated"
```

---

### Task 6: 实现 LibreOffice 转换和缓存

**Files:**
- Create: `src/main/java/com/example/sandbox/web/model/response/FilePreviewContent.java`
- Create: `src/main/java/com/example/sandbox/web/service/impl/OfficePreviewService.java`
- Create: `src/test/java/com/example/sandbox/web/service/impl/OfficePreviewServiceTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/example/sandbox/web/config/RagConfigProperties.java`

- [ ] **Step 1: 编写 Office 类型与路径安全测试**

```java
@Test
void classifiesOfficeExtensions() {
    assertThat(service.isConvertible("a.docx")).isTrue();
    assertThat(service.isConvertible("a.xlsx")).isTrue();
    assertThat(service.isConvertible("a.pptx")).isTrue();
    assertThat(service.isConvertible("a.pdf")).isFalse();
}

@Test
void rejectsPathsOutsideHomeGem() {
    assertThatThrownBy(() -> service.previewWorkspace(client, "/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 编写缓存复用测试**

模拟：

```java
when(client.execCommand(startsWith("sha256sum"))).thenReturn("abc123  input.docx");
when(client.fileExists(expectedPdfPath)).thenReturn(true);
when(client.downloadFile(expectedPdfPath)).thenReturn(PDF_BYTES);
```

断言没有执行包含 `soffice` 的命令。

- [ ] **Step 3: 编写转换命令测试**

缓存不存在时断言命令包含：

```text
timeout 120 soffice
--headless
--nologo
--nofirststartwizard
--norestore
-env:UserInstallation=file:///tmp/lo-profile-
--convert-to pdf
```

并断言转换后下载的是 `.preview` 或 `/home/gem/temp/previews` 中的 PDF。

- [ ] **Step 4: 运行测试并确认失败**

Run:

```powershell
mvn -Dtest=OfficePreviewServiceTest test
```

Expected: `OfficePreviewService` 和 `FilePreviewContent` 不存在。

- [ ] **Step 5: 添加预览响应模型**

```java
public record FilePreviewContent(
        byte[] content,
        String mediaType,
        String previewType,
        String originalFileName
) {
}
```

- [ ] **Step 6: 实现 OfficePreviewService**

公开方法：

```java
public boolean isConvertible(String fileName);

public FilePreviewContent previewKnowledge(
        AioSandboxClient client,
        KnowledgeDocumentEntity document,
        String sandboxPath);

public FilePreviewContent previewWorkspace(
        AioSandboxClient client,
        String sandboxPath);
```

关键规则：

```java
private static final Set<String> OFFICE_EXTENSIONS = Set.of(
        "doc", "docx", "odt", "rtf",
        "xls", "xlsx", "ods",
        "ppt", "pptx", "odp"
);
```

路径校验：

```java
private String requireHomeGemPath(String path) {
    if (path == null || !path.startsWith("/home/gem/") || path.contains("\0")) {
        throw new IllegalArgumentException("不允许预览该路径");
    }
    return path;
}
```

Shell 单引号转义：

```java
private String quote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
}
```

知识库缓存：

```text
/home/gem/knowledge/{kbId}/.preview/{docId}-{sourceHash}.pdf
```

普通缓存：

```text
/home/gem/temp/previews/{pathHash}-{sourceHash}.pdf
```

- [ ] **Step 7: 增加配置**

`application.yml`：

```yaml
rag:
  preview:
    conversion:
      enabled: true
      timeout-seconds: 120
```

`RagConfigProperties`：

```java
private Preview preview = new Preview();

@Getter
@Setter
public static class Preview {
    private Conversion conversion = new Conversion();
}

@Getter
@Setter
public static class Conversion {
    private boolean enabled = true;
    private int timeoutSeconds = 120;
}
```

- [ ] **Step 8: 运行 Office 服务测试**

Run:

```powershell
mvn -Dtest=OfficePreviewServiceTest test
```

Expected: 类型、路径、缓存和转换命令测试全部通过。

- [ ] **Step 9: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/model/response/FilePreviewContent.java src/main/java/com/example/sandbox/web/service/impl/OfficePreviewService.java src/test/java/com/example/sandbox/web/service/impl/OfficePreviewServiceTest.java src/main/java/com/example/sandbox/web/config/RagConfigProperties.java src/main/resources/application.yml
git commit -m "feat: add cached LibreOffice preview conversion"
```

---

### Task 7: 分别接入知识库预览与普通文件预览

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/KnowledgeService.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java`
- Modify: `src/main/java/com/example/sandbox/web/controller/RagController.java`
- Modify: `src/main/java/com/example/sandbox/web/controller/SandboxController.java`
- Test: `src/test/java/com/example/sandbox/web/controller/PreviewControllerTest.java`

- [ ] **Step 1: 编写知识库所有权测试**

验证以下接口访问其他用户资源时返回项目现有的 HTTP 401：

```text
POST /api/rag/bases/{kbId}/documents/upload
GET /api/rag/bases/{kbId}/documents
GET /api/rag/document/{docId}
GET /api/rag/document/{docId}/file
GET /api/rag/document/{docId}/chunks
PUT /api/rag/document/{docId}/replace
DELETE /api/rag/document/{docId}
```

当前用户文档的 Office 预览应返回：

```text
Content-Type: application/pdf
Content-Disposition: inline
```

- [ ] **Step 2: 编写普通 Office 预览测试**

模拟 `OfficePreviewService.previewWorkspace` 返回 PDF，断言：

```text
GET /api/sessions/s1/files/preview?path=/home/gem/uploads/a.docx
```

返回 `application/pdf`，并且不调用 `KnowledgeService.listChunks`。

- [ ] **Step 3: 运行控制器测试并确认失败**

Run:

```powershell
mvn -Dtest=PreviewControllerTest test
```

Expected: 当前接口返回原始 Office 字节，且切片接口缺少当前用户所有权校验。

- [ ] **Step 4: 将知识库操作统一改为用户感知接口**

在 `KnowledgeService` 中新增或修改为：

```java
List<KnowledgeDocumentEntity> listDocuments(Long userId, Long kbId);
KnowledgeDocumentEntity getDocument(Long userId, Long docId);
List<KnowledgeChunkEntity> listChunks(Long userId, Long docId);
void deleteDocument(Long userId, Long docId);
FilePreviewContent getPreviewContent(Long userId, Long docId);
```

`upload` 和 `listDocuments` 先调用统一的 `requireOwnedKnowledgeBase(userId, kbId)`；
文档详情、切片、预览、替换和删除统一调用 `requireOwnedDocument(userId, docId)`。
控制器不再先取无权限保护的实体再自行比较，避免以后新增调用方绕过校验。

预览实现流程：

1. 查询文档并校验 `document.userId == userId`。
2. 调用迁移服务确保本地规范文件存在。
3. 得到规范沙箱路径。
4. 沙箱文件不存在时，从本地二进制恢复该单个文件。
5. Office 文件调用 `OfficePreviewService.previewKnowledge`。
6. 非 Office 文件下载原始沙箱字节并返回原媒体类型。

- [ ] **Step 5: 修改 RagController 传递当前用户**

上传、列表、详情、切片、预览、替换和删除接口都从 `UserContext` 读取用户 ID，并调用上一步的用户感知方法。例如：

```java
Long userId = UserContext.getCurrentUserId();
List<KnowledgeChunkEntity> chunks = knowledgeService.listChunks(userId, docId);
knowledgeService.deleteDocument(userId, docId);
```

- [ ] **Step 6: 修改 RagController 文件响应**

```java
FilePreviewContent preview = knowledgeService.getPreviewContent(userId, docId);
return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(preview.originalFileName()))
        .contentType(MediaType.parseMediaType(preview.mediaType()))
        .body(preview.content());
```

- [ ] **Step 7: 修改普通文件预览**

`SandboxController.previewFile`：

```java
agentService.getSession(id);
AioSandboxClient client = sandboxClientFactory.getAioClient(id);
FilePreviewContent preview = officePreviewService.isConvertible(path)
        ? officePreviewService.previewWorkspace(client, path)
        : nativePreview(client, path);
```

普通接口不访问知识库切片。

- [ ] **Step 8: 保持下载接口返回原文件**

`RagController` 和 `SandboxController` 的下载逻辑不得使用转换后的 PDF。

- [ ] **Step 9: 运行控制器与服务测试**

Run:

```powershell
mvn -Dtest=PreviewControllerTest,OfficePreviewServiceTest test
```

Expected: 上传、列表、详情、切片、预览、替换、删除的所有权校验，以及知识库 Office 预览、普通 Office 预览和原文件下载测试通过。

- [ ] **Step 10: 提交该任务**

```powershell
git add src/main/java/com/example/sandbox/web/service/KnowledgeService.java src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java src/main/java/com/example/sandbox/web/controller/RagController.java src/main/java/com/example/sandbox/web/controller/SandboxController.java src/test/java/com/example/sandbox/web/controller/PreviewControllerTest.java
git commit -m "feat: route knowledge and workspace Office previews"
```

---

### Task 8: 让 FilePreviewer 使用独立 previewType

**Files:**
- Modify: `src/main/resources/static/js/components/FilePreviewer.js`
- Modify: `src/main/resources/static/js/api.js`
- Create: `src/test/js/file-previewer-office.test.js`

- [ ] **Step 1: 编写前端类型测试**

```javascript
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/components/FilePreviewer.js'),
    'utf8'
);

const sandbox = { console, window: {}, URL, Blob };
vm.createContext(sandbox);
vm.runInContext(source + '\nthis.previewTypeFor = FilePreviewer._previewTypeFor.bind(FilePreviewer);', sandbox);

assert.equal(sandbox.previewTypeFor('docx'), 'pdf');
assert.equal(sandbox.previewTypeFor('xlsx'), 'pdf');
assert.equal(sandbox.previewTypeFor('pptx'), 'pdf');
assert.equal(sandbox.previewTypeFor('png'), 'png');
assert.equal(sandbox.previewTypeFor('md'), 'md');
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
node src/test/js/file-previewer-office.test.js
```

Expected: `_previewTypeFor` 不存在。

- [ ] **Step 3: 增加 previewType 状态**

```javascript
previewType: '',

_previewTypeFor(ext) {
    const office = [
        'doc', 'docx', 'odt', 'rtf',
        'xls', 'xlsx', 'ods',
        'ppt', 'pptx', 'odp'
    ];
    return office.includes(ext) ? 'pdf' : ext;
},
```

在 `preview(opts)` 中：

```javascript
this.fileType = this._normalizeFileType(opts.fileType, opts.fileName, opts.filePath);
this.previewType = opts.previewType || this._previewTypeFor(this.fileType);
```

同时在 `_reset()`、`_syncView()`、Vue `data()`、`mounted()` 和可见性重置逻辑中同步维护 `previewType`，避免关闭后再次打开文件时沿用上一个文件的渲染类型。

- [ ] **Step 4: 按 previewType 创建 Blob 和选择渲染器**

知识库和普通文件加载方法都改为：

```javascript
const mime = this._mimeOf(this.previewType);
const blob = new Blob([buffer], { type: mime });
```

Vue computed：

```javascript
isImage() { return self._isImageType(this.previewType); },
isPdf() { return self._isPdfType(this.previewType); },
```

标题、图标和文件类型文字继续使用 `fileType`。

- [ ] **Step 5: 保留知识库切片分流**

保持：

```javascript
showChunks() {
    return this.source === 'knowledge' && this.chunks !== undefined;
}
```

`source === 'workspace'` 时不得调用 `_loadKnowledgeChunks`。

- [ ] **Step 6: 运行前端测试**

Run:

```powershell
node src/test/js/file-previewer-office.test.js
node src/test/js/api-path.test.js
```

Expected: 两个脚本退出码均为 0。

- [ ] **Step 7: 提交该任务**

```powershell
git add src/main/resources/static/js/components/FilePreviewer.js src/main/resources/static/js/api.js src/test/js/file-previewer-office.test.js
git commit -m "feat: render converted Office previews as PDF"
```

---

### Task 9: 更新文档并完成端到端验证

**Files:**
- Modify: `docs/frontend-file-preview.md`
- Modify: `README.md`
- Modify: `docs/project-config-reference.md`

- [ ] **Step 1: 更新目录和预览说明**

文档明确：

```text
本地主副本：
uploads/users/{userId}/knowledge/{kbId}/{fileName}
uploads/users/{userId}/uploads/{fileName}

沙箱副本：
/home/gem/knowledge/{kbId}/{fileName}
/home/gem/uploads/{fileName}
```

同时说明知识库双栏预览与普通单栏预览的差异。

- [ ] **Step 2: 运行完整自动化验证**

Run:

```powershell
mvn test
node src/test/js/api-path.test.js
node src/test/js/file-previewer-office.test.js
git diff --check
```

Expected:

- Maven `BUILD SUCCESS`
- Java 测试无失败
- 两个 Node 测试退出码为 0
- `git diff --check` 无输出

- [ ] **Step 3: 验证 LibreOffice 镜像**

Run:

```powershell
docker run --rm --entrypoint soffice agent-infra/sandbox-office:latest --version
docker run --rm --entrypoint sh agent-infra/sandbox-office:latest -c "id -un; test -x /opt/gem/run.sh"
```

Expected:

- 输出 LibreOffice 版本
- 默认入口仍可执行

- [ ] **Step 4: 手工验证用户级恢复**

1. 用户上传知识库文件 `说明书.docx`。
2. 用户普通上传 `截图.png`。
3. 确认本地存在：

```text
uploads/users/{userId}/knowledge/{kbId}/说明书.docx
uploads/users/{userId}/uploads/截图.png
```

4. 删除该用户沙箱。
5. 新建会话触发新沙箱。
6. 在沙箱终端执行：

```bash
test -f "/home/gem/knowledge/{kbId}/说明书.docx"
test -f "/home/gem/uploads/截图.png"
```

7. 确认两个命令退出码为 0。

- [ ] **Step 5: 手工验证两类 Office 预览**

知识库：

1. 点击 `说明书.docx`。
2. 左侧显示转换后的 PDF。
3. 右侧显示 MySQL 中的切片。
4. 再次打开时复用缓存。

普通文件：

1. 在工作空间打开 `/home/gem/uploads/说明书.docx`。
2. 只显示 PDF 预览。
3. 不发起 `/api/rag/document/{docId}/chunks` 请求。

- [ ] **Step 6: 验证旧文件迁移**

准备一个数据库 `storage_path` 指向 `uploads/knowledge/{userId}/doc_{id}.pdf`
的现有文档，触发沙箱重建后确认：

- 新规范路径存在。
- 数据库 `storage_path` 已更新。
- 文档仍可预览。
- 新文件大小与旧文件一致。

- [ ] **Step 7: 最终提交**

```powershell
git add docs README.md src
git commit -m "feat: persist user files and preview Office documents"
```
