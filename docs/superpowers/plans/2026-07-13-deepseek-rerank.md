# DeepSeek V4 Flash RAG Rerank Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用独立配置的 `deepseek-v4-flash` 替换云端 CPU BGE 重排，同时修复搜索 `topK` 与 `docName`，并在外部调用失败时退化为向量排序。

**Architecture:** 保留 `RerankService` 作为唯一领域边界，不修改接口与 `KnowledgeEnhancerImpl`。通过 `rag.enhancement.rerank.provider` 在 DeepSeek 和现有 BGE 实现之间选择唯一 Bean；DeepSeek 实现封装候选预筛、OpenAI 兼容请求、严格 JSON 解析、10 秒超时和向量降级。主流程模型只读取 `DEEPSEEK_LLM_MODEL`，重排模型只读取 `RAG_RERANK_MODEL`。

**Tech Stack:** Java 17、Spring Boot 3.2.5、WebFlux `WebClient`、Jackson、JUnit 5、AssertJ、Mockito、JDK `HttpServer`、Milvus、Maven。

## Global Constraints

- 不修改 `RerankService` 接口、`KnowledgeEnhancerImpl` 或主流程 LLM 调用链。
- 不新增模型注册中心、动态路由器或第三方依赖。
- 所有新增或修改的注释、Javadoc 和脚本注释使用中文。
- 新类、字段、方法必须有中文注释；超时和降级必须说明故障范围与原因。
- 只局部合并当前已修改的 `application.yml` 和 `docs/project-spec.md`，不得覆盖用户已有改动。
- 不打印、写入或提交 API Key。
- `src/test/` 被 `.gitignore` 忽略；用户后续明确要求提交时，对新增测试使用 `git add -f <exact-path>`。本次默认不提交、不推送。
- 第一版不自动重试；失败立即降级，避免叠加关键路径延迟。

---

## File Structure

**Create**

- `src/main/java/com/example/sandbox/web/service/enhance/impl/DeepSeekRerankServiceImpl.java`：DeepSeek 协议、候选限制、响应校验、超时和向量降级。
- `src/test/java/com/example/sandbox/web/config/RerankConfigurationTest.java`：模型配置隔离。
- `src/test/java/com/example/sandbox/web/service/enhance/impl/DeepSeekRerankServiceImplTest.java`：请求、映射和降级。
- `src/test/java/com/example/sandbox/web/service/impl/KnowledgeServiceSearchTest.java`：页面 `topK` 与文档名。

**Modify**

- `src/main/java/com/example/sandbox/web/config/RagConfigProperties.java:109-117`
- `src/main/java/com/example/sandbox/web/service/enhance/impl/BgeRerankServiceImpl.java:1-28`
- `src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java:472-548`
- `src/main/resources/application.yml:163-166`
- `docs/project-spec.md` 第八章末尾

---

### Task 1: 独立重排配置

**Files:**
- Create: `src/test/java/com/example/sandbox/web/config/RerankConfigurationTest.java`
- Modify: `src/main/java/com/example/sandbox/web/config/RagConfigProperties.java:109-117`

**Interfaces:**
- Consumes: `RagConfigProperties.Enhancement.Rerank`
- Produces: provider、URL、Key、独立 model、topK、候选、字符、超时、输出 Token 配置

- [ ] **Step 1: 写失败测试**

```java
package com.example.sandbox.web.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 RAG 重排配置与主流程模型相互独立。 */
class RerankConfigurationTest {

    /** 验证主流程使用 Pro 时，重排仍默认使用独立 Flash。 */
    @Test
    void rerankModelIsIndependentFromMainModel() {
        AgentConfigProperties agent = new AgentConfigProperties();
        agent.getLlm().getExecutor().setModel("deepseek-v4-pro");
        RagConfigProperties.Enhancement.Rerank rerank =
                new RagConfigProperties().getEnhancement().getRerank();

        assertThat(agent.getLlm().getExecutor().getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(rerank.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(rerank.getProvider()).isEqualTo("deepseek");
    }

    /** 验证请求体和延迟限制具有安全默认值。 */
    @Test
    void rerankHasBoundedDefaults() {
        RagConfigProperties.Enhancement.Rerank rerank =
                new RagConfigProperties().getEnhancement().getRerank();

        assertThat(rerank.getTopK()).isEqualTo(8);
        assertThat(rerank.getMaxCandidates()).isEqualTo(12);
        assertThat(rerank.getMaxContentChars()).isEqualTo(1200);
        assertThat(rerank.getTimeoutSeconds()).isEqualTo(10);
        assertThat(rerank.getMaxTokens()).isEqualTo(512);
    }
}
```

- [ ] **Step 2: 验证测试先失败**

Run:

```powershell
mvn "-Dtest=RerankConfigurationTest" test
```

Expected: 编译失败，提示新增 getter 不存在，或默认模型断言失败。

- [ ] **Step 3: 最小扩充配置对象**

```java
@Setter
@Getter
public static class Rerank {
    /** 是否启用外部重排；关闭后按向量分数排序。 */
    private boolean enabled = true;
    /** 重排提供方，可选 deepseek 或 bge。 */
    private String provider = "deepseek";
    /** 重排服务基础地址。 */
    private String apiUrl = "https://api.deepseek.com";
    /** 重排服务 API Key；不得写入日志。 */
    private String apiKey = "";
    /** 仅供重排使用的模型名，不继承主流程模型名。 */
    private String model = "deepseek-v4-flash";
    /** 默认最终重排条数。 */
    private int topK = 8;
    /** 发送给模型的最大候选数。 */
    private int maxCandidates = 12;
    /** 单个候选发送给模型的最大字符数。 */
    private int maxContentChars = 1200;
    /** 外部重排总超时秒数。 */
    private int timeoutSeconds = 10;
    /** 外部模型最大输出 Token 数。 */
    private int maxTokens = 512;
}
```

- [ ] **Step 4: 验证测试转绿**

Run: `mvn "-Dtest=RerankConfigurationTest" test`

Expected: `Tests run: 2, Failures: 0, Errors: 0`。

---

### Task 2: DeepSeek 重排实现与 Bean 隔离

**Files:**
- Create: `src/test/java/com/example/sandbox/web/service/enhance/impl/DeepSeekRerankServiceImplTest.java`
- Create: `src/main/java/com/example/sandbox/web/service/enhance/impl/DeepSeekRerankServiceImpl.java`
- Modify: `src/main/java/com/example/sandbox/web/service/enhance/impl/BgeRerankServiceImpl.java:1-28`

**Interfaces:**
- Consumes: unchanged `RerankService.rerank(String, List<RawChunk>)`
- Produces: provider=deepseek 时的唯一 `RerankService` Bean

- [ ] **Step 1: 写本地 HTTP 成功路径失败测试**

沿用现有 `DeepSeekLlmServiceErrorHandlingTest` 的 `HttpServer` 模式。核心测试必须是：

```java
/** 验证请求使用独立 Flash 模型并按模型顺序映射结果。 */
@Test
void reranksWithIndependentFlashModel() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    DeepSeekRerankServiceImpl service = createService(exchange -> {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        respond(exchange, 200, """
                {"choices":[{"message":{"content":"{\"results\":[{\"index\":1,\"score\":0.96},{\"index\":0,\"score\":0.71}]}"}}]}
                """);
    }, 12, 10);

    List<RankedChunk> ranked = service.rerank("Spring Boot", List.of(
            new RawChunk(1L, 0, "Java 集合", 0.8f),
            new RawChunk(1L, 1, "Spring Boot 应用", 0.7f)));

    assertThat(ranked).extracting(RankedChunk::chunkIndex).containsExactly(1, 0);
    JsonNode sent = objectMapper.readTree(requestBody.get());
    assertThat(sent.path("model").asText()).isEqualTo("deepseek-v4-flash");
    assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled");
    assertThat(requestBody.get()).doesNotContain("deepseek-v4-pro", "tools");
}
```

测试辅助方法使用以下精确配置：

```java
private DeepSeekRerankServiceImpl createService(ExchangeHandler handler,
                                                  int maxCandidates,
                                                  int timeoutSeconds) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", handler::handle);
    server.start();
    RagConfigProperties properties = new RagConfigProperties();
    var config = properties.getEnhancement().getRerank();
    config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
    config.setApiKey("test-key");
    config.setModel("deepseek-v4-flash");
    config.setMaxCandidates(maxCandidates);
    config.setTimeoutSeconds(timeoutSeconds);
    config.setTopK(8);
    return new DeepSeekRerankServiceImpl(properties, objectMapper);
}
```

- [ ] **Step 2: 验证测试先失败**

Run: `mvn "-Dtest=DeepSeekRerankServiceImplTest" test`

Expected: 编译失败，提示 `DeepSeekRerankServiceImpl` 不存在。

- [ ] **Step 3: 创建最小 DeepSeek 实现**

类边界固定为：

```java
@Service
@ConditionalOnProperty(
        prefix = "rag.enhancement.rerank",
        name = "provider",
        havingValue = "deepseek",
        matchIfMissing = true)
public class DeepSeekRerankServiceImpl implements RerankService {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekRerankServiceImpl.class);
    private static final String SYSTEM_PROMPT = """
            你是知识库检索重排器。请根据用户查询判断候选片段的语义相关性。
            只返回 JSON，不要解释，不要 Markdown，不要补充候选中不存在的内容。
            结果按相关性从高到低排列，每个候选最多出现一次。
            """;

    private final RagConfigProperties config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /** 使用独立 RAG 配置创建 DeepSeek 重排客户端。 */
    public DeepSeekRerankServiceImpl(RagConfigProperties config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        var rerank = config.getEnhancement().getRerank();
        WebClient.Builder builder = WebClient.builder().baseUrl(rerank.getApiUrl());
        if (rerank.getApiKey() != null && !rerank.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rerank.getApiKey());
        }
        this.webClient = builder.build();
    }

    /**
     * 执行一次批量重排。
     * HTTP、超时、空响应和协议错误均不重试，立即按向量分数降级。
     */
    @Override
    public List<RankedChunk> rerank(String query, List<RawChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        var rerank = config.getEnhancement().getRerank();
        int topK = Math.max(0, Math.min(rerank.getTopK(), candidates.size()));
        if (!rerank.isEnabled() || query == null || query.isBlank()) {
            return fallbackSort(candidates, topK);
        }
        List<RawChunk> selected = candidates.stream()
                .sorted(Comparator.comparing(RawChunk::score).reversed())
                .limit(Math.max(1, rerank.getMaxCandidates()))
                .toList();
        long started = System.nanoTime();
        try {
            JsonNode response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(buildRequest(query, selected, topK, rerank))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(Math.max(1, rerank.getTimeoutSeconds())))
                    .block();
            List<RankedChunk> ranked = parseAndFill(response, selected, topK);
            log.info("重排完成: provider=deepseek, model={}, candidates={}, topK={}, elapsedMs={}, fallback=false",
                    rerank.getModel(), selected.size(), topK, elapsedMillis(started));
            return ranked;
        } catch (Exception e) {
            log.warn("重排降级: provider=deepseek, model={}, candidates={}, topK={}, elapsedMs={}, reason={}",
                    rerank.getModel(), selected.size(), topK, elapsedMillis(started),
                    e.getClass().getSimpleName(), e);
            return fallbackSort(candidates, topK);
        }
    }
}
```

同类内实现以下私有方法，不创建额外抽象：

```java
private Map<String, Object> buildRequest(String query, List<RawChunk> selected,
        int topK, RagConfigProperties.Enhancement.Rerank config) throws JsonProcessingException;
private List<RankedChunk> parseAndFill(JsonNode response,
        List<RawChunk> selected, int topK) throws JsonProcessingException;
private List<RankedChunk> fallbackSort(List<RawChunk> candidates, int topK);
private String truncate(String content, int maxChars);
private String extractJsonObject(String content) throws JsonProcessingException;
private long elapsedMillis(long startedNanos);
```

实现规则：

1. 请求包含 `model`、两条 `messages`、`temperature=0`、`max_tokens`、`thinking={type:disabled}`，不包含 `tools`。
2. 用户消息是 `ObjectMapper` 序列化的 `{query,topK,candidates:[{index,text}]}`；文本按 `maxContentChars` 截断。
3. 响应从 `choices[0].message.content` 读取；取第一个 `{` 到最后一个 `}`，兼容代码围栏。
4. 忽略重复和越界索引；合法项不足时按 `selected` 向量分数补足且不重复。
5. 降级对原始全部候选按 score 降序并限制为 topK。
6. 日志不记录 query、候选正文、Key 或原始响应。

- [ ] **Step 4: 给 BGE Bean 增加选择条件**

```java
@Service
@ConditionalOnProperty(
        prefix = "rag.enhancement.rerank",
        name = "provider",
        havingValue = "bge")
public class BgeRerankServiceImpl implements RerankService {
    // 现有逻辑保持不变
}
```

- [ ] **Step 5: 增加候选限制、非法 JSON 和超时测试**

必须覆盖：

```java
@Test
void fallsBackToVectorScoresForInvalidJson() throws Exception {
    DeepSeekRerankServiceImpl service = createService(
            exchange -> respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}"),
            12, 10);
    List<RankedChunk> ranked = service.rerank("q", List.of(
            new RawChunk(1L, 0, "low", 0.1f),
            new RawChunk(1L, 1, "high", 0.9f)));
    assertThat(ranked).extracting(RankedChunk::chunkIndex).containsExactly(1, 0);
}

@Test
void fallsBackAfterTimeout() throws Exception {
    DeepSeekRerankServiceImpl service = createService(exchange -> {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        respond(exchange, 200, "{}");
    }, 12, 1);
    long started = System.nanoTime();
    List<RankedChunk> ranked = service.rerank("q", List.of(
            new RawChunk(1L, 0, "low", 0.1f),
            new RawChunk(1L, 1, "high", 0.9f)));
    assertThat(ranked).extracting(RankedChunk::chunkIndex).containsExactly(1, 0);
    assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isLessThan(Duration.ofSeconds(2));
}
```

候选限制测试读取发送的第二条 message，断言只含向量分数最高的 `maxCandidates` 条。

- [ ] **Step 6: 验证测试转绿**

Run: `mvn "-Dtest=RerankConfigurationTest,DeepSeekRerankServiceImplTest" test`

Expected: 所有配置与重排测试通过，`Failures: 0, Errors: 0`。

---

### Task 3: 修复 Knowledge 搜索出口

**Files:**
- Create: `src/test/java/com/example/sandbox/web/service/impl/KnowledgeServiceSearchTest.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java:472-548`

**Interfaces:**
- Consumes: unchanged `RerankService.rerank(String, List<RawChunk>)`
- Produces: 返回不超过页面请求 topK，且 docName 非空

- [ ] **Step 1: 写失败测试**

测试使用 Mockito + `ReflectionTestUtils` 注入 `queryRewriteService`、`embeddingService`、`vectorStoreService`、`chunkRepository`、`documentRepository`、`rerankService`。构造 8 条向量与重排结果，并执行：

```java
List<Map<String, Object>> results = service.search(2L, 3L, "query", 5);

assertThat(results).hasSize(5);
assertThat(results).allSatisfy(
        row -> assertThat(row.get("docName")).isEqualTo("resume.pdf"));
```

关键 stub：

```java
when(rewrite.rewrite("query", List.of())).thenReturn(List.of("query"));
when(embedding.embedBatch(List.of("query")))
        .thenReturn(new EmbeddingService.EmbeddingResult(List.of(new float[]{0.1f}), 1));
when(vectorStore.search(eq(2L), eq(3L), any(float[].class), eq(20)))
        .thenReturn(vectorRows);
when(chunks.findByDocumentIdIn(List.of(1L))).thenReturn(chunkRows);
when(rerank.rerank(eq("query"), anyList())).thenReturn(rankedRows);
when(documents.findAllById(List.of(1L))).thenReturn(List.of(document));
```

- [ ] **Step 2: 验证测试先失败**

Run: `mvn "-Dtest=KnowledgeServiceSearchTest" test`

Expected: 当前实现返回 8 条或 `docName=null`，断言失败。

- [ ] **Step 3: 批量构建文档名映射**

删除从 Milvus 结果读取 `r.get("docName")` 的循环。在 `docIds` 计算后加入：

```java
Map<Long, String> docNameMap = documentRepository.findAllById(docIds).stream()
        .collect(Collectors.toMap(
                KnowledgeDocumentEntity::getId,
                document -> Optional.ofNullable(document.getFileName()).orElse("未知文档"),
                (first, ignored) -> first));
```

新增 `java.util.stream.Collectors` 导入。保留缺失元数据时的 `getOrDefault(..., "未知文档")`。

- [ ] **Step 4: 在页面出口截断 topK**

```java
int resultLimit = Math.max(0, topK);
for (RankedChunk rc : ranked.stream().limit(resultLimit).toList()) {
    Map<String, Object> m = new HashMap<>();
    m.put("docId", rc.docId());
    m.put("docName", docNameMap.getOrDefault(rc.docId(), "未知文档"));
    m.put("chunkIndex", rc.chunkIndex());
    m.put("content", rc.content());
    m.put("score", rc.score());
    results.add(m);
}
```

不修改 `RerankService` 和 Agent 调用方。

- [ ] **Step 5: 验证测试转绿**

Run: `mvn "-Dtest=KnowledgeServiceSearchTest" test`

Expected: `Failures: 0, Errors: 0`。

---

### Task 4: 配置绑定与 ADR

**Files:**
- Modify: `src/main/resources/application.yml:163-166`
- Modify: `docs/project-spec.md` 第八章末尾

- [ ] **Step 1: 仅替换 rerank 配置段**

```yaml
    rerank:
      enabled: ${RAG_RERANK_ENABLED:true}
      provider: ${RAG_RERANK_PROVIDER:deepseek}
      api-url: ${RAG_RERANK_API_URL:${DEEPSEEK_LLM_URL:https://api.deepseek.com}}
      api-key: ${RAG_RERANK_API_KEY:${DEEPSEEK_API_KEY:}}
      model: ${RAG_RERANK_MODEL:deepseek-v4-flash}
      top-k: ${RAG_RERANK_TOP_K:8}
      max-candidates: ${RAG_RERANK_MAX_CANDIDATES:12}
      max-content-chars: ${RAG_RERANK_MAX_CONTENT_CHARS:1200}
      timeout-seconds: ${RAG_RERANK_TIMEOUT_SECONDS:10}
      max-tokens: ${RAG_RERANK_MAX_TOKENS:512}
```

不得修改当前已有的主流程 Pro、`agent.time-zone` 或其他未提交配置。

- [ ] **Step 2: 在现有 ADR-017 后追加 ADR-018**

ADR 内容必须记录：

- 决策：独立 `RAG_RERANK_MODEL`、provider 选择唯一 Bean、失败向量降级。
- 排除：继承主模型、通用模型路由器、云端 CPU BGE、完全关闭重排。
- 理由：现有 `RerankService` 已是足够边界，同协议换模型只改环境变量。
- 约束：不记录正文/Key，不重试，页面 topK 出口截断，Agent 使用默认 top-k。

- [ ] **Step 3: 验证局部差异**

Run:

```powershell
git diff --check -- src/main/resources/application.yml docs/project-spec.md
git diff -- src/main/resources/application.yml docs/project-spec.md
```

Expected: 第一条命令无输出；diff 保留用户现有 Pro、时间和检查点 ADR，只新增 rerank 配置和 ADR-018。

---

### Task 5: 本地回归与打包

**Files:** Verify only

- [ ] **Step 1: 运行新增定向测试**

Run:

```powershell
mvn "-Dtest=RerankConfigurationTest,DeepSeekRerankServiceImplTest,KnowledgeServiceSearchTest" test
```

Expected: `Failures: 0, Errors: 0`。

- [ ] **Step 2: 运行相关现有测试**

Run:

```powershell
mvn "-Dtest=NoopVectorStoreServiceImplTest,KnowledgeFileMigrationServiceTest" test
```

Expected: `Failures: 0, Errors: 0`。

- [ ] **Step 3: 打包**

Run: `mvn "-Dmaven.test.skip=true" package`

Expected: `BUILD SUCCESS` 和 `target/sandbox-1.0-SNAPSHOT.jar`。

- [ ] **Step 4: 审核范围与敏感信息**

Run:

```powershell
git diff --check
git diff --stat
rg -n "sk-[A-Za-z0-9]|Bearer [A-Za-z0-9]" src/main src/test docs/project-spec.md
```

Expected: diff check 无输出；没有真实 Key；没有无关文件被改动。

---

### Task 6: 云端配置、部署与真实检索

**Files:**
- Remote modify: `/etc/webagent/webagent.env`
- Deploy: `target/sandbox-1.0-SNAPSHOT.jar`

- [ ] **Step 1: 备份并幂等更新非敏感环境变量**

```bash
sudo cp /etc/webagent/webagent.env /etc/webagent/webagent.env.before-rerank
sudo sed -i '/^DEEPSEEK_LLM_MODEL=/d;/^RAG_RERANK_PROVIDER=/d;/^RAG_RERANK_MODEL=/d;/^RAG_RERANK_TIMEOUT_SECONDS=/d;/^RAG_RERANK_MAX_CANDIDATES=/d' /etc/webagent/webagent.env
sudo sh -c 'printf "\nDEEPSEEK_LLM_MODEL=deepseek-v4-pro\nRAG_RERANK_PROVIDER=deepseek\nRAG_RERANK_MODEL=deepseek-v4-flash\nRAG_RERANK_TIMEOUT_SECONDS=10\nRAG_RERANK_MAX_CANDIDATES=12\n" >> /etc/webagent/webagent.env'
```

不要设置或输出 `RAG_RERANK_API_KEY`，让配置回退到已有 `DEEPSEEK_API_KEY`。

- [ ] **Step 2: 部署已验证 JAR**

Run locally:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy.ps1 -SkipBuild -KeyPath "$env:USERPROFILE\.ssh\webagent-vm_key.pem"
```

Expected: 远端服务重启成功，本机与公网健康检查 HTTP 200。

- [ ] **Step 3: 验证配置和启动日志**

确认主流程模型为 `deepseek-v4-pro`、provider=`deepseek`、重排模型为 `deepseek-v4-flash`，日志中不存在 `NoUniqueBeanDefinitionException`、`BeanDefinitionOverrideException` 或 `APPLICATION FAILED`。

- [ ] **Step 4: 连续执行三次真实检索**

在服务器内部复用数据库已有 Token，但不得打印 Token。请求：

```json
{"query":"Java Spring Boot","topK":5}
```

调用 `POST http://127.0.0.1:8081/api/rag/bases/1/search`。仅输出 `code`、`data.length`、`docName`、`chunkIndex`、`score` 和总耗时。Expected:

- HTTP 200，`data.length=5`。
- 所有 `docName` 非空。
- 日志包含 `provider=deepseek`、`model=deepseek-v4-flash`。
- 正常请求不访问 `127.0.0.1:8002`。
- 重排超过 10 秒时仍返回向量降级结果。

- [ ] **Step 5: 验证超时降级并恢复配置**

临时设 `RAG_RERANK_TIMEOUT_SECONDS=1`，重启后检索一次，确认 HTTP 200 且日志包含 `重排降级`；随后恢复为 `10` 并再次重启验证。不要停止或删除 BGE Adapter/TEI。

- [ ] **Step 6: 最终核对**

Run:

```powershell
Invoke-WebRequest -Uri 'http://104.208.99.11/' -UseBasicParsing -TimeoutSec 20
git status --short
```

Expected: 公网 HTTP 200；本地只有本任务和用户原有未提交改动，没有自动提交或推送。

---

## Rollback

1. 恢复 `/etc/webagent/webagent.env.before-rerank`。
2. 恢复部署前 JAR，或设置 `RAG_RERANK_PROVIDER=bge` 和 `RAG_RERANK_API_URL=http://127.0.0.1:8002/rerank`。
3. 执行 `sudo systemctl restart webagent`。
4. 重新验证首页和知识库搜索。

回滚不删除 Milvus 数据、知识库文档或本地 BGE 服务。
