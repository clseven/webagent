package com.example.sandbox.web.controller;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import com.example.sandbox.web.service.impl.UserWorkspaceStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileUploadControllerTest {

    private final AgentService agentService = mock(AgentService.class);
    private final SandboxServiceImpl sandboxService = mock(SandboxServiceImpl.class);
    private final UserWorkspaceStorageService storage = mock(UserWorkspaceStorageService.class);
    private final AioSandboxClient client = mock(AioSandboxClient.class);
    private final FileUploadController controller = new FileUploadController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "agentService", agentService);
        ReflectionTestUtils.setField(controller, "sandboxService", sandboxService);
        ReflectionTestUtils.setField(controller, "workspaceStorage", storage);
        ConversationSession session = mock(ConversationSession.class);
        when(session.getUserId()).thenReturn(7L);
        when(agentService.getSession("s1")).thenReturn(session);
        when(sandboxService.getAioClient("s1")).thenReturn(client);
    }

    @Test
    void savesLocallyBeforeUploadingToSandbox() {
        byte[] bytes = new byte[] {1, 2, 3};
        Path local = Path.of("uploads/users/7/uploads/report.docx");
        when(storage.sanitizeFileName("report.docx")).thenReturn("report.docx");
        when(storage.uploadFile(7L, "report.docx")).thenReturn(local);
        when(storage.uploadSandboxPath("report.docx"))
                .thenReturn("/home/gem/uploads/report.docx");
        when(client.uploadFile("/home/gem/uploads/report.docx", bytes)).thenReturn(true);

        Object result = controller.upload(
                new MockMultipartFile("file", "report.docx",
                        "application/octet-stream", bytes),
                "s1");

        assertThat(result).isInstanceOf(ApiResponse.class);
        assertThat(((ApiResponse<?>) result).getData())
                .isEqualTo("/home/gem/uploads/report.docx");
        var order = inOrder(storage, client);
        order.verify(storage).write(local, bytes);
        order.verify(client).uploadFile("/home/gem/uploads/report.docx", bytes);
    }
}
