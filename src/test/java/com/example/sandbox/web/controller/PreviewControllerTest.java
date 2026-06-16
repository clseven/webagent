package com.example.sandbox.web.controller;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.response.FilePreviewContent;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.impl.OfficePreviewService;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import com.example.sandbox.web.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreviewControllerTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void returnsConvertedKnowledgeOfficeFileInline() {
        UserContext.setCurrentUserId(7L);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(knowledgeService.getPreviewContent(7L, 12L))
                .thenReturn(new FilePreviewContent(
                        new byte[] {1, 2}, "application/pdf", "pdf", "report.docx"));
        RagController controller = new RagController();
        ReflectionTestUtils.setField(controller, "knowledgeService", knowledgeService);

        var response = controller.getFile(12L);

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .startsWith("inline;");
        assertThat(response.getBody()).containsExactly(1, 2);
    }

    @Test
    void returnsConvertedWorkspaceOfficeFileWithoutKnowledgeCalls() throws Exception {
        AgentService agentService = mock(AgentService.class);
        SandboxClientFactory clientFactory = mock(SandboxClientFactory.class);
        OfficePreviewService officePreviewService = mock(OfficePreviewService.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        AioClient client = mock(AioClient.class);
        when(agentService.getSession("s1")).thenReturn(mock(ConversationSession.class));
        when(clientFactory.getAioClient("s1")).thenReturn(client);
        when(officePreviewService.isConvertible("/home/gem/uploads/a.docx")).thenReturn(true);
        when(officePreviewService.previewWorkspace(client, "/home/gem/uploads/a.docx"))
                .thenReturn(new FilePreviewContent(
                        new byte[] {3, 4}, "application/pdf", "pdf", "a.docx"));
        SandboxController controller = new SandboxController();
        ReflectionTestUtils.setField(controller, "agentService", agentService);
        ReflectionTestUtils.setField(controller, "sandboxClientFactory", clientFactory);
        ReflectionTestUtils.setField(controller, "officePreviewService", officePreviewService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.previewFile("s1", "/home/gem/uploads/a.docx", response);

        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getContentAsByteArray()).containsExactly(3, 4);
        verify(knowledgeService, never()).listChunks(7L, 12L);
    }

    @Test
    void rejectsChunksForAnotherUsersDocument() {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(12L);
        document.setUserId(8L);
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        when(repository.findById(12L)).thenReturn(java.util.Optional.of(document));
        KnowledgeServiceImpl service = new KnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "documentRepository", repository);

        assertThatThrownBy(() -> service.listChunks(7L, 12L))
                .isInstanceOf(UnauthorizedException.class);
    }
}
