package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * 文件状态检查 Hook（State Checks，走法 C：写前现场验证）。
 *
 * <h3>要防什么</h3>
 * <p>模型对文件状态持"过时认知"时仍执行写操作，三种：没读就改、读过但中间被改了、盲写覆盖。
 * 主要价值在并发落地后防 TOCTOU 竞态；单线程串行下基本不触发，属正常待命。</p>
 *
 * <h3>机制</h3>
 * <ul>
 *   <li><b>Post on {@code read_file}</b>：读完本地算 hash 存入 {@link FileCognitionState}。</li>
 *   <li><b>Pre on {@code write_file} / {@code file_replace}</b>：
 *     新建放行；覆盖已有文件时，没读过→拦，re-read 算 hash 与记录不符→拦，一致→放行并刷新记录。
 *     大文件（超阈值）降级为只查"读过没"，跳过 hash 校验。</li>
 *   <li><b>Post on {@code write_file} / {@code file_replace}</b>：写后清除该路径记录（invalidate），
 *     使旧认知作废，模型再改同一文件需重读。</li>
 * </ul>
 *
 * <h3>拦截语义</h3>
 * <p>Pre Hook 返回非 null 字符串即拦下工具、把字符串作为 observation 回传模型（沿用既有 Pre 语义）。</p>
 *
 * <h3>并发注意</h3>
 * <p>并发落地后，本 hook 的 "re-read + verify + write" 必须整体在 per-session 写锁临界区内，
 * 否则 verify 通过后、write 之前仍可能被并发 write 插入修改（TOCTOU）。当前单线程下不涉及。</p>
 */
public class FileStateCheckHook implements ReactAgent.PreToolUseHook, ReactAgent.PostToolUseHook {

    private static final Logger log = LoggerFactory.getLogger(FileStateCheckHook.class);

    /** 写工具：内容整体覆盖，参数名 path。 */
    private static final String WRITE_FILE = "write_file";

    /** 写工具：局部替换，参数名 file。 */
    private static final String FILE_REPLACE = "file_replace";

    /** 读工具，参数名 path。 */
    private static final String READ_FILE = "read_file";

    /** 大文件降级阈值（字节）：超过则跳过 hash 校验，只查"读过没"。 */
    static final int LARGE_FILE_THRESHOLD_BYTES = 1024 * 1024;

    /** 沙箱客户端工厂，用于写前 re-read。 */
    private final SandboxClientFactory sandboxClientFactory;

    /** 文件认知状态存储。 */
    private final FileCognitionState cognitionState;

    /**
     * 创建文件状态检查 Hook。
     *
     * @param sandboxClientFactory 沙箱客户端工厂
     * @param cognitionState       文件认知状态存储
     */
    public FileStateCheckHook(SandboxClientFactory sandboxClientFactory, FileCognitionState cognitionState) {
        this.sandboxClientFactory = sandboxClientFactory;
        this.cognitionState = cognitionState;
    }

    // ==================== Pre：写前校验 ====================

    /**
     * 写工具执行前做现场校验；读工具及其他工具放行。
     *
     * @param toolCall  工具调用
     * @param sessionId 会话 ID
     * @param tools     当前可用工具表（未使用）
     * @return null 放行；非 null 为拦截原因，作为 observation 回传模型
     */
    @Override
    public String run(LlmToolCall toolCall, String sessionId, Map<String, Tool> tools) {
        if (toolCall == null) {
            return null;
        }
        String name = toolCall.name();
        if (!WRITE_FILE.equals(name) && !FILE_REPLACE.equals(name)) {
            return null;
        }
        String path = extractPath(toolCall);
        if (path == null || path.isBlank()) {
            return null; // 参数缺失交由工具自身校验报错
        }

        SandboxClient client;
        try {
            client = sandboxClientFactory.getClient(sessionId);
        } catch (Exception e) {
            log.warn("State Checks 无法获取沙箱客户端，放行写操作: path={}, reason={}", path, e.getMessage());
            return null;
        }

        // 新建 vs 覆盖：文件不存在 → 新建，直接放行，不要求先读
        boolean exists;
        try {
            exists = client.fileExists(path);
        } catch (Exception e) {
            log.warn("State Checks 判断文件存在失败，保守当作新建放行: path={}, reason={}", path, e.getMessage());
            return null;
        }
        if (!exists) {
            return null;
        }

        // 覆盖已有文件：必须读过
        if (!cognitionState.hasRead(sessionId, path)) {
            log.info("State Checks 拦截：未读先改 path={}", path);
            return buildNotReadReminder(path);
        }

        // re-read 现场算 hash，与记录比对（一次 re-read 同时确认"读过"+"没过时"）
        String current;
        try {
            current = client.readFile(path);
        } catch (Exception e) {
            log.warn("State Checks 写前 re-read 失败，放行写操作: path={}, reason={}", path, e.getMessage());
            return null;
        }
        if (current == null) {
            return null;
        }

        // 大文件降级：只查"读过没"（上面已确认），跳过 hash 校验
        if (current.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > LARGE_FILE_THRESHOLD_BYTES) {
            log.debug("State Checks 大文件降级，跳过 hash 校验 path={}", path);
            return null;
        }

        String currentHash = FileCognitionState.hash(current);
        Optional<String> recordedHash = cognitionState.getHash(sessionId, path);
        if (recordedHash.isPresent() && !recordedHash.get().equals(currentHash)) {
            log.info("State Checks 拦截：文件已过时 path={}", path);
            return buildStaleReminder(path);
        }

        // 一致：放行，并把记录刷新为最新（写前 re-read 的内容即当前最新认知）
        cognitionState.remember(sessionId, path, current);
        return null;
    }

    // ==================== Post：read 存 hash / write 清 hash ====================

    /**
     * 读工具执行后存内容 hash；写工具执行后清除该路径记录。
     *
     * @param toolCall  工具调用
     * @param result    工具执行结果
     * @param sessionId 会话 ID
     * @return 始终 null（本 hook 不向对话注入消息）
     */
    @Override
    public ChatMessage run(LlmToolCall toolCall, String result, String sessionId) {
        if (toolCall == null) {
            return null;
        }
        String name = toolCall.name();
        if (READ_FILE.equals(name)) {
            String path = extractPath(toolCall);
            if (path != null && !path.isBlank() && result != null && !isErrorResult(result)) {
                cognitionState.remember(sessionId, path, result);
            }
        } else if (WRITE_FILE.equals(name) || FILE_REPLACE.equals(name)) {
            String path = extractPath(toolCall);
            if (path != null && !path.isBlank()) {
                cognitionState.invalidate(sessionId, path);
            }
        }
        return null;
    }

    // ==================== 辅助 ====================

    /**
     * 从工具调用参数提取文件路径。write_file/read_file 用 path，file_replace 用 file。
     *
     * @param toolCall 工具调用
     * @return 文件路径；缺失时为 null
     */
    private String extractPath(LlmToolCall toolCall) {
        Map<String, Object> args = toolCall.arguments();
        if (args == null) {
            return null;
        }
        Object path = args.get("path");
        if (path == null) {
            path = args.get("file");
        }
        return path != null ? path.toString() : null;
    }

    /**
     * 粗略判断 read_file 结果是否为错误串，避免把错误信息当文件内容存 hash。
     *
     * @param result 工具结果
     * @return true 表示疑似错误结果
     */
    private boolean isErrorResult(String result) {
        return result.startsWith("读取失败：") || result.startsWith("错误：");
    }

    /**
     * 构建"未读先改"提醒。
     *
     * @param path 文件路径
     * @return 提醒文本
     */
    private String buildNotReadReminder(String path) {
        return "文件状态检查：你正在修改一个尚未读取过的已存在文件「" + path + "」。"
                + "请先 read_file 确认当前内容，再基于最新内容修改，避免 old_str 凭记忆填写导致误替换或覆盖。";
    }

    /**
     * 构建"文件已过时"提醒。
     *
     * @param path 文件路径
     * @return 提醒文本
     */
    private String buildStaleReminder(String path) {
        return "文件状态检查：文件「" + path + "」自你上次读取后已发生变化（可能被命令、并发工具或外部改动）。"
                + "请重新 read_file 获取最新内容，再基于最新内容修改，避免覆盖他人改动。";
    }
}
