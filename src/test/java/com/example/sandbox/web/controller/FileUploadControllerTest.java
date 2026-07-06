package com.example.sandbox.web.controller;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.file.AioFileApi;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.FileStorageService;
import com.example.sandbox.web.service.impl.OfficePreviewAsyncService;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import com.example.sandbox.web.service.impl.UserWorkspaceStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证文件上传控制器按用户工作目录读写上传文件。
 *
 * <p>这些用例刻意通过 sessionId 获取会话，再断言实际文件路径落在 userId
 * 对应的用户目录下，避免后续回退到会话目录隔离。</p>
 */
class FileUploadControllerTest {

    /** 上传流程使用的会话服务 mock。 */
    private final AgentService uploadAgentService = mock(AgentService.class);

    /** 上传流程使用的沙箱服务 mock。 */
    private final SandboxServiceImpl uploadSandboxService = mock(SandboxServiceImpl.class);

    /** 上传流程使用的用户工作空间存储 mock。 */
    private final UserWorkspaceStorageService uploadStorage = mock(UserWorkspaceStorageService.class);

    /** 上传流程使用的 Office 文件异步预转换服务 mock。 */
    private final OfficePreviewAsyncService uploadOfficePreviewAsyncService = mock(OfficePreviewAsyncService.class);

    /** 上传流程使用的 AIO 客户端 mock。 */
    private final AioClient uploadClient = mock(AioClient.class);

    /** 上传流程使用的 AIO 文件 API mock。 */
    private final AioFileApi uploadFiles = mock(AioFileApi.class);

    /** 上传流程使用的控制器实例。 */
    private final FileUploadController uploadController = new FileUploadController();

    /** 测试使用的临时存储根目录。 */
    @TempDir
    Path tempDir;

    /**
     * 初始化上传流程测试使用的控制器依赖。
     */
    @BeforeEach
    void setUpUploadController() {
        ReflectionTestUtils.setField(uploadController, "agentService", uploadAgentService);
        ReflectionTestUtils.setField(uploadController, "sandboxService", uploadSandboxService);
        ReflectionTestUtils.setField(uploadController, "workspaceStorage", uploadStorage);
        ReflectionTestUtils.setField(uploadController, "officePreviewAsyncService", uploadOfficePreviewAsyncService);
        ConversationSession session = mock(ConversationSession.class);
        when(session.getUserId()).thenReturn(7L);
        when(uploadAgentService.getSession("s1")).thenReturn(session);
        when(uploadSandboxService.getAioClient("s1")).thenReturn(uploadClient);
        when(uploadClient.files()).thenReturn(uploadFiles);
    }

    /**
     * 上传时应先写入用户工作空间，再同步到 AIO 沙箱。
     */
    @Test
    void savesLocallyBeforeUploadingToSandbox() {
        byte[] bytes = new byte[] {1, 2, 3};
        Path local = Path.of("uploads/users/7/uploads/report.docx");
        when(uploadStorage.sanitizeFileName("report.docx")).thenReturn("report.docx");
        when(uploadStorage.uploadFile(7L, "report.docx")).thenReturn(local);
        when(uploadStorage.uploadSandboxPath("report.docx"))
                .thenReturn("/home/gem/uploads/report.docx");
        when(uploadFiles.upload("/home/gem/uploads/report.docx", bytes)).thenReturn(true);

        Object result = uploadController.upload(
                new MockMultipartFile("file", "report.docx",
                        "application/octet-stream", bytes),
                "s1");

        assertThat(result).isInstanceOf(ApiResponse.class);
        assertThat(((ApiResponse<?>) result).getData())
                .isEqualTo("/home/gem/uploads/report.docx");
        var order = inOrder(uploadStorage, uploadFiles);
        order.verify(uploadStorage).write(local, bytes);
        order.verify(uploadFiles).upload("/home/gem/uploads/report.docx", bytes);
        verify(uploadOfficePreviewAsyncService)
                .convertWorkspaceFileAsync(7L, "/home/gem/uploads/report.docx");
    }

    /**
     * 下载上传文件时应通过会话所属用户读取用户级 uploads 目录。
     *
     * @throws Exception 当响应体读取失败时抛出
     */
    @Test
    void downloadReadsFromUserUploadDirectory() throws Exception {
        UserWorkspaceStorageService workspaceStorage = workspaceStorage();
        Long userId = 42L;
        String sessionId = "session-a";
        String filename = "report.txt";
        byte[] content = "用户级文件".getBytes(StandardCharsets.UTF_8);
        workspaceStorage.write(workspaceStorage.uploadFile(userId, filename), content);

        FileStorageService legacyStorage = mock(FileStorageService.class);
        when(legacyStorage.getFile(anyString(), anyString()))
                .thenThrow(new IllegalStateException("不应读取会话目录"));

        FileUploadController controller = controller(sessionId, userId, legacyStorage, workspaceStorage);

        ResponseEntity<InputStreamResource> response = controller.download(sessionId, filename);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo(content);
        verifyNoInteractions(legacyStorage);
    }

    /**
     * 删除上传文件时应删除会话所属用户的用户级 uploads 目录文件。
     */
    @Test
    void deleteRemovesFileFromUserUploadDirectory() {
        UserWorkspaceStorageService workspaceStorage = workspaceStorage();
        Long userId = 42L;
        String sessionId = "session-a";
        String filename = "report.txt";
        Path file = workspaceStorage.uploadFile(userId, filename);
        workspaceStorage.write(file, "用户级文件".getBytes(StandardCharsets.UTF_8));

        FileStorageService legacyStorage = mock(FileStorageService.class);
        FileUploadController controller = controller(sessionId, userId, legacyStorage, workspaceStorage);

        ApiResponse<Void> response = controller.delete(sessionId, filename);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(workspaceStorage.exists(file)).isFalse();
        verifyNoInteractions(legacyStorage);
    }

    /**
     * 创建使用临时目录的用户工作空间存储服务。
     *
     * @return 用户工作空间存储服务
     */
    private UserWorkspaceStorageService workspaceStorage() {
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        return new UserWorkspaceStorageService(properties);
    }

    /**
     * 创建只装配当前测试所需依赖的文件控制器。
     *
     * @param sessionId        会话 ID
     * @param userId           会话所属用户 ID
     * @param legacyStorage    旧文件存储服务 mock
     * @param workspaceStorage 用户工作空间存储服务
     * @return 文件上传控制器
     */
    private FileUploadController controller(String sessionId,
                                            Long userId,
                                            FileStorageService legacyStorage,
                                            UserWorkspaceStorageService workspaceStorage) {
        ConversationSession session = new ConversationSession();
        ReflectionTestUtils.setField(session, "userId", userId);

        AgentService agentService = mock(AgentService.class);
        when(agentService.getSession(sessionId)).thenReturn(session);

        FileUploadController controller = new FileUploadController();
        ReflectionTestUtils.setField(controller, "agentService", agentService);
        ReflectionTestUtils.setField(controller, "workspaceStorage", workspaceStorage);
        return controller;
    }
}
