package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.response.FilePreviewContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficePreviewServiceTest {

    private static final byte[] PDF_BYTES = new byte[] {0x25, 0x50, 0x44, 0x46};

    private final RagConfigProperties properties = new RagConfigProperties();
    private final OfficePreviewService service = new OfficePreviewService(properties);

    @Test
    void classifiesOfficeExtensions() {
        assertThat(service.isConvertible("a.docx")).isTrue();
        assertThat(service.isConvertible("a.xlsx")).isTrue();
        assertThat(service.isConvertible("a.pptx")).isTrue();
        assertThat(service.isConvertible("a.pdf")).isFalse();
    }

    @Test
    void rejectsPathsOutsideHomeGem() {
        assertThatThrownBy(() -> service.previewWorkspace(
                mock(AioSandboxClient.class), "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.previewWorkspace(
                mock(AioSandboxClient.class), "/home/gem/../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reusesKnowledgePreviewCache() {
        AioSandboxClient client = mock(AioSandboxClient.class);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(12L);
        document.setKbId(8L);
        document.setFileName("report.docx");
        String cached = "/home/gem/knowledge/8/.preview/12-abc123.pdf";
        when(client.execCommand(startsWith("sha256sum"))).thenReturn("abc123  report.docx");
        when(client.fileExists(cached)).thenReturn(true);
        when(client.downloadFile(cached)).thenReturn(PDF_BYTES);

        FilePreviewContent preview = service.previewKnowledge(
                client, document, "/home/gem/knowledge/8/report.docx");

        assertThat(preview.content()).containsExactly(PDF_BYTES);
        assertThat(preview.previewType()).isEqualTo("pdf");
        verify(client, never()).execCommand(contains("soffice"));
    }

    @Test
    void invokesLibreOfficeWithIsolatedProfileWhenCacheIsMissing() {
        AioSandboxClient client = mock(AioSandboxClient.class);
        when(client.execCommand(startsWith("sha256sum"))).thenReturn("def456  sheet.xlsx");
        when(client.fileExists(startsWith("/home/gem/temp/previews/")))
                .thenReturn(false, true);
        when(client.downloadFile(startsWith("/home/gem/temp/previews/")))
                .thenReturn(PDF_BYTES);

        FilePreviewContent preview =
                service.previewWorkspace(client, "/home/gem/uploads/sheet.xlsx");

        assertThat(preview.mediaType()).isEqualTo("application/pdf");
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).execCommand(command.capture());
        String conversionCommand = command.getAllValues().stream()
                .filter(value -> value.contains("soffice"))
                .findFirst()
                .orElseThrow();
        assertThat(conversionCommand)
                .contains("timeout 120 soffice")
                .contains("--headless")
                .contains("--nologo")
                .contains("--nofirststartwizard")
                .contains("--norestore")
                .contains("-env:UserInstallation=file:///tmp/lo-profile-")
                .contains("--convert-to pdf");
    }
}
