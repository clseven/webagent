package com.example.sandbox.aio.sandbox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO Sandbox 环境上下文。
 *
 * <p>字段与 `/v1/sandbox` 顶层响应保持一致；不同镜像未返回的扩展字段允许为空。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AioSandboxContext {

    /** Sandbox 用户主目录。 */
    @JsonProperty("home_dir")
    private String homeDir;

    /** 可选的工作目录扩展字段。 */
    private String workspace;

    /** AIO Sandbox 服务版本。 */
    private String version;

    /** @return Sandbox 用户主目录 */
    public String getHomeDir() {
        return homeDir;
    }

    /** @param homeDir Sandbox 用户主目录 */
    public void setHomeDir(String homeDir) {
        this.homeDir = homeDir;
    }

    /** @return 可选的工作目录 */
    public String getWorkspace() {
        return workspace;
    }

    /** @param workspace 可选的工作目录 */
    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    /** @return AIO Sandbox 服务版本 */
    public String getVersion() {
        return version;
    }

    /** @param version AIO Sandbox 服务版本 */
    public void setVersion(String version) {
        this.version = version;
    }
}
