package com.example.sandbox.aio;

import com.example.sandbox.aio.browser.AioBrowserApi;
import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.file.AioFileApi;
import com.example.sandbox.aio.node.AioNodeApi;
import com.example.sandbox.aio.sandbox.AioSandboxApi;
import com.example.sandbox.aio.sandbox.model.AioSandboxContext;
import com.example.sandbox.aio.shell.AioShellApi;
import com.example.sandbox.aio.utility.AioUtilityApi;
import com.example.sandbox.web.service.SandboxClient;

import java.time.Duration;

/**
 * AIO Sandbox 领域 API 的聚合入口。
 *
 * <p>业务代码通过领域访问器调用 REST 能力；实现 `SandboxClient` 仅用于兼容仍依赖
 * 通用 Sandbox 抽象的少量调用点。</p>
 */
public class AioClient implements SandboxClient {

    /** Sandbox 环境 API。 */
    private final AioSandboxApi sandbox;

    /** Shell API。 */
    private final AioShellApi shell;

    /** File API。 */
    private final AioFileApi files;

    /** Browser API。 */
    private final AioBrowserApi browser;

    /** Node.js API。 */
    private final AioNodeApi node;

    /** Utility API。 */
    private final AioUtilityApi utility;

    /**
     * 根据动态 AIO endpoint 创建聚合客户端。
     *
     * @param baseUrl AIO 基础地址
     */
    public AioClient(String baseUrl) {
        AioHttpClient http = new AioHttpClient(baseUrl);
        this.sandbox = new AioSandboxApi(http);
        this.shell = new AioShellApi(http);
        this.files = new AioFileApi(http);
        this.browser = new AioBrowserApi(http);
        this.node = new AioNodeApi(http);
        this.utility = new AioUtilityApi(http);
    }

    /** @return Sandbox 环境 API */
    public AioSandboxApi sandbox() {
        return sandbox;
    }

    /** @return Shell API */
    public AioShellApi shell() {
        return shell;
    }

    /** @return File API */
    public AioFileApi files() {
        return files;
    }

    /** @return Browser API */
    public AioBrowserApi browser() {
        return browser;
    }

    /** @return Node.js API */
    public AioNodeApi node() {
        return node;
    }

    /** @return Utility API */
    public AioUtilityApi utility() {
        return utility;
    }

    /**
     * 执行同步 Shell 命令并返回标准输出。
     *
     * @param command Shell 命令
     * @return 标准输出
     */
    @Override
    public String execCommand(String command) {
        return shell.exec(command).getOutput();
    }

    /**
     * 读取 UTF-8 文本文件。
     *
     * @param path 文件路径
     * @return 文件内容
     */
    @Override
    public String readFile(String path) {
        return files.readText(path);
    }

    /**
     * 写入 UTF-8 文本文件。
     *
     * @param path    文件路径
     * @param content 文件内容
     */
    @Override
    public void writeFile(String path, String content) {
        if (!files.writeText(path, content)) {
            throw new IllegalStateException("写入 Sandbox 文件失败: " + path);
        }
    }

    /**
     * 下载文件字节。
     *
     * @param path 文件路径
     * @return 文件字节
     */
    @Override
    public byte[] downloadFile(String path) {
        return files.download(path);
    }

    /**
     * 截取浏览器 PNG。
     *
     * @return PNG 字节
     */
    @Override
    public byte[] screenshot() {
        return browser.screenshot();
    }

    /**
     * 获取通用 Sandbox 上下文。
     *
     * @return 通用上下文
     */
    @Override
    public SandboxContext getContext() {
        AioSandboxContext source = sandbox.getContext();
        SandboxContext context = new SandboxContext();
        context.setHomeDir(source.getHomeDir());
        context.setWorkspace(source.getWorkspace());
        return context;
    }

    /**
     * 检查 AIO 是否在十秒内就绪。
     *
     * @return 是否就绪
     */
    @Override
    public boolean isReady() {
        return shell.waitUntilReady(Duration.ofSeconds(10));
    }

    /**
     * 等待 AIO 在十秒沙箱启动时限内就绪。
     *
     * @return 是否就绪
     */
    public boolean waitForReady() {
        return shell.waitUntilReady(Duration.ofSeconds(10));
    }
}
