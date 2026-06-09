package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserWorkspaceStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsMatchingKnowledgePaths() {
        UserWorkspaceStorageService service = service();

        assertThat(service.knowledgeFile(7L, 13L, "合同.pdf"))
                .isEqualTo(tempDir.resolve("users/7/knowledge/13/合同.pdf"));
        assertThat(service.knowledgeSandboxPath(13L, "合同.pdf"))
                .isEqualTo("/home/gem/knowledge/13/合同.pdf");
    }

    @Test
    void buildsUserScopedUploadPaths() {
        UserWorkspaceStorageService service = service();

        assertThat(service.uploadFile(7L, "data.xlsx"))
                .isEqualTo(tempDir.resolve("users/7/uploads/data.xlsx"));
        assertThat(service.uploadSandboxPath("data.xlsx"))
                .isEqualTo("/home/gem/uploads/data.xlsx");
    }

    @Test
    void stripsPathSegmentsAndRejectsBlankNames() {
        UserWorkspaceStorageService service = service();

        assertThat(service.sanitizeFileName("../folder/report.pdf"))
                .isEqualTo("report.pdf");
        assertThat(service.sanitizeFileName("C:\\temp\\report.pdf"))
                .isEqualTo("report.pdf");
        assertThatThrownBy(() -> service.sanitizeFileName(".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UserWorkspaceStorageService service() {
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        return new UserWorkspaceStorageService(properties);
    }
}
