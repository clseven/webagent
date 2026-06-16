# Browser Agent 运行时设计

## 目标

为 AIO Sandbox 中的浏览器增加基于 CDP 的语义观察和通用操作能力。大模型负责理解任务并组合操作，固定的 Browser Agent 脚本负责连接浏览器、读取页面结构和可靠执行原子动作。

在增加 Browser Agent 前，先拆分当前集中在单个 `AioSandboxClient` 中的 AIO REST 调用。拆分覆盖项目当前使用的 Sandbox、Shell、File、Browser 和 Utility API，并加入 Browser Agent 所需的 Node.js API。不为尚未使用的全部 OpenAPI 路径预先生成代码。

第一阶段完成 AIO 客户端分层、新沙箱的 Browser Agent 运行时安装基础，
并向模型开放受约束的 Playwright 函数体执行能力。不处理已有沙箱的自动补装。

## AIO 客户端分层

基础设施代码按 AIO REST 领域拆分：

```text
com.example.sandbox.aio
├── AioClient.java
├── core/
│   ├── AioHttpClient.java
│   ├── AioResponse.java
│   └── AioApiException.java
├── sandbox/
│   └── AioSandboxApi.java
├── shell/
│   ├── AioShellApi.java
│   └── model/
├── file/
│   ├── AioFileApi.java
│   └── model/
├── browser/
│   ├── AioBrowserApi.java
│   └── model/
├── node/
│   ├── AioNodeApi.java
│   └── model/
└── utility/
    └── AioUtilityApi.java
```

职责边界：

- `AioHttpClient` 只负责 WebClient、超时、二进制响应、JSON envelope 和 HTTP 错误映射。
- 领域 API 类严格对应 OpenAPI 路径和字段，不包含 Agent 提示、工具结果文案或业务流程。
- 请求和响应优先使用类型模型；仅对 OpenAPI 中明确为自由对象的字段保留 `Map<String, Object>`。
- `AioClient` 是聚合入口，通过 `shell()`、`files()`、`browser()`、`node()`、`sandbox()` 和 `utility()` 暴露领域 API。
- `SandboxClientFactory` 和 Sandbox 生命周期服务负责按会话或用户解析 endpoint 并创建 `AioClient`。
- Agent Tool 只依赖领域 API 或更高层 Gateway，不直接拼接 AIO HTTP 请求。

迁移完成后删除旧的集中式 `AioSandboxClient`，不长期保留兼容门面。

## 运行时目录

Browser Agent 安装到隐藏目录：

```text
/home/gem/.runtime/browser-agent/
├── package.json
├── package-lock.json
├── browser-agent.mjs
└── node_modules/
```

该目录属于系统运行时基础设施，不属于用户工作空间：

- 不写入 `/home/gem/README.md`。
- 不加入用户工作空间恢复和同步范围。
- 不作为普通文件工具的推荐访问目录。
- 后端浏览器工具通过固定绝对路径调用脚本。

## 新沙箱初始化

`SandboxServiceImpl.initAioDirectories()` 在完成现有目录和工具脚本初始化时，增加以下步骤：

1. 创建 `/home/gem/.runtime/browser-agent`。
2. 从应用资源上传 `browser-agent.mjs` 和 `package.json`。
3. 在运行时目录执行固定版本依赖安装：

   ```bash
   npm install --omit=dev --no-audit --no-fund
   ```

4. 验证 `playwright-core` 可以从该目录加载。
5. 记录初始化成功或明确的失败原因。

依赖版本必须在 `package.json` 中固定，避免新建沙箱因上游自动升级获得不一致行为。不执行 `playwright install`，因为脚本通过 CDP 连接 AIO 已启动的 Chrome。

## 失败行为

Browser Agent 初始化失败不阻止整个 Sandbox 创建，因为文件、Shell、知识库和其他 Agent 能力仍可使用。

初始化过程不自动重试安装。网络、npm registry、磁盘或权限错误应记录完整日志。后续语义浏览器工具发现运行时缺失时，返回明确的“Browser Agent 运行环境未初始化”错误，不在工具调用过程中隐式安装依赖。

## Browser Agent 脚本边界

`browser-agent.mjs` 是固定的通用驱动器，而不是特定网站流程脚本。第一版承载：

- `connectActivePage`：连接 AIO 管理的 Chrome 并选择活动页面。
- `inspect`：返回 URL、标题、可见文本、视口和交互元素。
- `health`：验证运行时和 CDP 连接。

模型通过两个工具使用该运行时：

- `browser_inspect` 调用固定的 `inspect`，模型不能提交代码。
- `browser_execute` 接收使用预绑定 `page` 的异步 JavaScript 函数体。

`browser_execute` 的后端包装器负责 Playwright 导入、CDP 连接、结果序列化和断开连接。
模型代码不能 import/require、访问 Node 进程或文件系统、启动浏览器、新建或关闭
page/context。该静态检查是模型使用约束，不替代 AIO Sandbox 的安全隔离。

推荐操作链为：

```text
browser_inspect -> browser_execute -> browser_inspect/browser_screenshot
```

原有 `browser_action` 保留为坐标和键鼠兜底，每次只执行一个原子动作。

## CDP 连接

后端通过 `GET /v1/browser/info` 读取响应 `data.cdp_url`。Browser Agent 使用 `playwright-core` 的 `chromium.connectOverCDP(cdpUrl)` 连接当前 AIO Chrome，复用其标签页、Cookie 和登录状态。

Node.js 执行环境本身可以是临时的；浏览器状态保存在持续运行的 Chrome 实例中。

## 安全约束

- 不向模型暴露 `cdp_url`；后端自动注入连接信息。
- `browser_execute` 仅接收 Playwright 函数体，不接收完整 Node.js 程序。
- 操作目标优先使用 `browser_inspect` 返回的可访问名称、文本和 selector。
- 导航或页面显著变化后，旧 selector 和坐标可能失效，模型必须重新观察。
- 截图和坐标操作保留为 DOM 语义操作失败时的兜底能力。
- 密码、验证码、支付、发送、删除等敏感行为由上层确认策略控制，不写死在 Browser Agent 脚本中。

## 测试

初始化测试覆盖：

- 运行时目录创建命令。
- 两个资源文件上传。
- npm 安装命令在正确目录执行并使用固定参数。
- 依赖验证命令执行。
- npm 安装失败不会让沙箱创建失败。

后续语义工具测试覆盖：

- 从 `browser/info.data.cdp_url` 获取连接地址。
- 观察页面返回稳定的结构化结果。
- 通过元素引用点击和输入。
- 页面变化后旧引用被拒绝。
- CDP 连接失败、页面不存在和元素不可操作时返回明确错误。
