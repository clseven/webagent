package com.example.sandbox.web.controller;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.exception.DuplicateFileException;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.FileStorageService;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import com.example.sandbox.web.service.impl.UserWorkspaceStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件上传下载 API
 *
 * @author example
 * @date 2026/05/20
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private SandboxServiceImpl sandboxService;

    @Autowired
    private UserWorkspaceStorageService workspaceStorage;

    @PostMapping("/upload")
    public Object upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "文件不能为空");
        }
        var session = agentService.getSession(sessionId);

        try {
            Long userId = session.getUserId();
            String filename = workspaceStorage.sanitizeFileName(file.getOriginalFilename());
            var localPath = workspaceStorage.uploadFile(userId, filename);
            if (workspaceStorage.exists(localPath)) {
                throw new DuplicateFileException(filename, null);
            }
            byte[] bytes = file.getBytes();
            workspaceStorage.write(localPath, bytes);
            String sandboxPath = workspaceStorage.uploadSandboxPath(filename);
            syncUploadToSandbox(sessionId, sandboxPath, bytes);
            return ApiResponse.success(sandboxPath);
        } catch (DuplicateFileException e) {
            throw e;  // 让 GlobalExceptionHandler 统一处理
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "文件上传失败");
        }
    }

    /**
     * 替换沙箱中已有的文件（用于处理重复文件时选择"替换"）
     */
    @PutMapping("/upload/replace")
    public ApiResponse<String> replaceFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "文件不能为空");
        }
        var session = agentService.getSession(sessionId);

        try {
            String filename = workspaceStorage.sanitizeFileName(file.getOriginalFilename());
            byte[] bytes = file.getBytes();
            workspaceStorage.write(
                    workspaceStorage.uploadFile(session.getUserId(), filename),
                    bytes);
            String sandboxPath = workspaceStorage.uploadSandboxPath(filename);
            syncUploadToSandbox(sessionId, sandboxPath, bytes);
            return ApiResponse.success(sandboxPath);
        } catch (Exception e) {
            log.error("替换沙箱文件失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "替换失败: " + e.getMessage());
        }
    }

    @GetMapping("/download/{sessionId}/{filename}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        try {
            agentService.getSession(sessionId);
            InputStream inputStream = fileStorageService.getFile(sessionId, filename);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(inputStream));
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{sessionId}/{filename}")
    public ApiResponse<Void> delete(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        try {
            agentService.getSession(sessionId);
            fileStorageService.delete(sessionId, filename);
            return ApiResponse.success();
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.getMessage());
            return ApiResponse.error(500, "文件删除失败");
        }
    }

    /**
     * 获取存储路径信息（供调试用）
     */
    @GetMapping("/path/{sessionId}")
    public ApiResponse<StoragePathInfo> getStoragePath(@PathVariable String sessionId) {
        return ApiResponse.success(new StoragePathInfo(
                fileStorageService.getStoragePath(sessionId),
                fileStorageService.getMountPath()
        ));
    }

    public record StoragePathInfo(String hostPath, String containerPath) {}

    private void syncUploadToSandbox(String sessionId, String sandboxPath, byte[] bytes) {
        try {
            AioSandboxClient client = sandboxService.getAioClient(sessionId);
            if (client.uploadFile(sandboxPath, bytes)) {
                log.info("文件已同步到 AIO 沙箱: {}", sandboxPath);
            } else {
                log.warn("文件已持久化，但同步到 AIO 沙箱失败: {}", sandboxPath);
            }
        } catch (Exception e) {
            log.warn("文件已持久化，当前沙箱同步稍后重试: {}", sandboxPath, e);
        }
    }
}
