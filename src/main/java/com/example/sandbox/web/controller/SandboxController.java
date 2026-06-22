package com.example.sandbox.web.controller;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.request.ExecuteRequest;
import com.example.sandbox.web.model.request.FileWriteRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.model.response.FilePreviewContent;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import com.example.sandbox.web.service.impl.OfficePreviewService;
import com.example.sandbox.web.service.mcpclient.McpClientToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 沙盒操作 API
 *
 * @author example
 * @date 2026/05/14
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
public class SandboxController {

    @Autowired
    private SandboxService sandboxService;

    @Autowired
    private SandboxServiceImpl sandboxServiceImpl;

    @Autowired
    private SandboxClientFactory sandboxClientFactory;

    @Autowired
    private AgentService agentService;

    @Autowired
    private OfficePreviewService officePreviewService;

    /** MCP 动态工具提供器，工作区刷新时显式重新加载用户 MCP 配置。 */
    @Autowired
    private McpClientToolProvider mcpToolProvider;

    /**
     * 执行命令
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<ExecutionResult> executeCommand(
            @PathVariable String id,
            @RequestBody ExecuteRequest request) {
        String command = request.getCommand();
        String commandPreview = command != null && command.length() > 200
                ? command.substring(0, 200) + "..."
                : command;
        log.info("收到沙箱命令执行请求: session={}, command={}", id, commandPreview);
        agentService.getSession(id);
        Instant start = Instant.now();
        AioClient client = sandboxClientFactory.getAioClient(id);
        String output = client.execCommand(command);
        Duration duration = Duration.between(start, Instant.now());
        log.info("沙箱命令执行完成: session={}, durationMs={}, outputLength={}",
                id, duration.toMillis(), output != null ? output.length() : 0);
        return ApiResponse.success(ExecutionResult.success(output, duration));
    }

    /**
     * 读取文件
     */
    @PostMapping("/{id}/files/read")
    public ApiResponse<ExecutionResult> readFile(
            @PathVariable String id,
            @RequestBody FileWriteRequest request) {
        agentService.getSession(id);
        Instant start = Instant.now();
        AioClient client = sandboxClientFactory.getAioClient(id);
        String content = client.readFile(request.getPath());
        Duration duration = Duration.between(start, Instant.now());
        return ApiResponse.success(ExecutionResult.success(content, duration));
    }

    @PostMapping("/{id}/files/write")
    public ApiResponse<ExecutionResult> writeFile(
            @PathVariable String id,
            @RequestBody FileWriteRequest request) {
        agentService.getSession(id);
        Instant start = Instant.now();
        AioClient client = sandboxClientFactory.getAioClient(id);
        client.writeFile(request.getPath(), request.getContent());
        Duration duration = Duration.between(start, Instant.now());
        return ApiResponse.success(ExecutionResult.success("File written: " + request.getPath(), duration));
    }

    @GetMapping("/{id}/aio/endpoint")
    public ApiResponse<String> getAioEndpoint(@PathVariable String id) {
        agentService.getSession(id);
        String endpoint = sandboxServiceImpl.getAioEndpoint(id);
        return ApiResponse.success(endpoint);
    }

    /**
     * 刷新工作空间相关状态。
     *
     * <p>前端工作空间刷新按钮会调用本接口。当前主要清理 MCP 工具发现缓存，
     * 让用户在沙箱内新增或调整 MCP Server 后，下一轮对话能重新发现最新工具。</p>
     *
     * @param id 会话 ID
     * @return 空成功响应
     */
    @PostMapping("/{id}/workspace/refresh")
    public ApiResponse<Void> refreshWorkspace(@PathVariable String id) {
        log.info("刷新工作空间状态: session={}", id);
        agentService.getSession(id);
        mcpToolProvider.evict(id);
        return ApiResponse.success();
    }

    /**
     * 预览沙箱内文件（inline 内联渲染）
     * <p>用于前端树形目录点击预览 PDF/图片/代码 等。</p>
     * <p>返回正确的 Content-Type，浏览器可内联渲染。</p>
     */
    @GetMapping("/{id}/files/preview")
    public void previewFile(@PathVariable String id, @RequestParam String path, HttpServletResponse response) {
        try {
            agentService.getSession(id);
            requireHomeGemPath(path);
            AioClient client = sandboxClientFactory.getAioClient(id);
            FilePreviewContent preview = officePreviewService.isConvertible(path)
                    ? officePreviewService.previewWorkspace(client, path)
                    : nativePreview(client, path);
            byte[] fileContent = preview.content();

            if (fileContent == null || fileContent.length == 0) {
                response.setContentType(preview.mediaType());
                setContentDisposition(response, preview.originalFileName(), "inline");
                response.setContentLength(0);
                return;
            }

            response.setContentType(preview.mediaType());
            setContentDisposition(response, preview.originalFileName(), "inline");
            response.setContentLength(fileContent.length);
            response.getOutputStream().write(fileContent);
        } catch (Exception e) {
            log.error("预览文件失败: {}", e.getMessage(), e);
            response.setStatus(500);
        }
    }

    /**
     * 下载沙箱内文件（attachment 强制下载）
     * <p>用于 agent 工具返回下载链接、用户主动下载文件。</p>
     */
    @GetMapping("/{id}/files/download")
    public void downloadFile(@PathVariable String id, @RequestParam String path, HttpServletResponse response) {
        try {
            agentService.getSession(id);
            requireHomeGemPath(path);
            AioClient client = sandboxClientFactory.getAioClient(id);
            byte[] fileContent = client.files().download(path);

            if (fileContent == null || fileContent.length == 0) {
                response.setStatus(404);
                return;
            }

            String filename = path.substring(path.lastIndexOf('/') + 1);
            response.setContentType(resolveMimeType(path));
            setContentDisposition(response, filename, "attachment");
            response.setContentLength(fileContent.length);
            response.getOutputStream().write(fileContent);
        } catch (Exception e) {
            log.error("下载文件失败: {}", e.getMessage(), e);
            response.setStatus(500);
        }
    }

    /**
     * 设置 Content-Disposition 头，支持中文文件名（RFC 5987）
     */
    private void setContentDisposition(HttpServletResponse response, String filename, String disposition) {
        try {
            String encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
            // RFC 5987 格式：同时提供 ASCII fallback 和 UTF-8 编码
            response.setHeader("Content-Disposition",
                    disposition + "; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 总是支持，这里不会发生
            response.setHeader("Content-Disposition", disposition + "; filename=\"download\"");
        }
    }

    /**
     * 根据文件扩展名推断 MIME 类型
     */
    private String resolveMimeType(String path) {
        if (path == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String ext = path.substring(dotIdx + 1).toLowerCase();
        Map<String, String> mimeMap = Map.ofEntries(
                Map.entry("pdf", "application/pdf"),
                Map.entry("png", "image/png"),
                Map.entry("jpg", "image/jpeg"),
                Map.entry("jpeg", "image/jpeg"),
                Map.entry("gif", "image/gif"),
                Map.entry("svg", "image/svg+xml"),
                Map.entry("webp", "image/webp"),
                Map.entry("bmp", "image/bmp"),
                Map.entry("ico", "image/x-icon"),
                Map.entry("txt", "text/plain;charset=UTF-8"),
                Map.entry("md", "text/markdown;charset=UTF-8"),
                Map.entry("markdown", "text/markdown;charset=UTF-8"),
                Map.entry("json", "application/json;charset=UTF-8"),
                Map.entry("xml", "application/xml;charset=UTF-8"),
                Map.entry("yml", "text/yaml;charset=UTF-8"),
                Map.entry("yaml", "text/yaml;charset=UTF-8"),
                Map.entry("csv", "text/csv;charset=UTF-8"),
                Map.entry("html", "text/html;charset=UTF-8"),
                Map.entry("htm", "text/html;charset=UTF-8"),
                Map.entry("css", "text/css;charset=UTF-8"),
                Map.entry("js", "application/javascript;charset=UTF-8"),
                Map.entry("ts", "application/typescript;charset=UTF-8")
        );
        return mimeMap.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private FilePreviewContent nativePreview(AioClient client, String path) {
        byte[] content = client.files().download(path);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        String extension = "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            extension = filename.substring(dot + 1).toLowerCase();
        }
        return new FilePreviewContent(
                content, resolveMimeType(path), extension, filename);
    }

    private void requireHomeGemPath(String path) {
        if (path == null || !path.startsWith("/home/gem/") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("不允许访问该路径");
        }
        for (String segment : path.substring("/home/gem/".length()).split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("不允许访问该路径");
            }
        }
    }
}
