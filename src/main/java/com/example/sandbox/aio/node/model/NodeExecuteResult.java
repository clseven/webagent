package com.example.sandbox.aio.node.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * AIO Node.js 代码执行结果。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeExecuteResult {

    /** 执行状态。 */
    private String status;

    /** 被执行的代码。 */
    private String code;

    /** 标准输出。 */
    private String stdout;

    /** 标准错误。 */
    private String stderr;

    /** 进程退出码。 */
    @JsonProperty("exit_code")
    private int exitCode;

    /** 结构化执行输出。 */
    private List<Map<String, Object>> outputs;

    /** @return 执行状态 */
    public String getStatus() {
        return status;
    }

    /** @param status 执行状态 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return 被执行的代码 */
    public String getCode() {
        return code;
    }

    /** @param code 被执行的代码 */
    public void setCode(String code) {
        this.code = code;
    }

    /** @return 标准输出 */
    public String getStdout() {
        return stdout;
    }

    /** @param stdout 标准输出 */
    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    /** @return 标准错误 */
    public String getStderr() {
        return stderr;
    }

    /** @param stderr 标准错误 */
    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    /** @return 进程退出码 */
    public int getExitCode() {
        return exitCode;
    }

    /** @param exitCode 进程退出码 */
    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    /** @return 结构化执行输出 */
    public List<Map<String, Object>> getOutputs() {
        return outputs;
    }

    /** @param outputs 结构化执行输出 */
    public void setOutputs(List<Map<String, Object>> outputs) {
        this.outputs = outputs;
    }
}
