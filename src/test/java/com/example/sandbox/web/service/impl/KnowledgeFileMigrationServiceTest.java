package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeFileMigrationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyFileBeforeUpdatingStoragePath() throws Exception {
        Path legacy = tempDir.resolve("knowledge/4/doc_12.pdf");
        Files.createDirectories(legacy.getParent());
        byte[] bytes = new byte[] {1, 2, 3, 4};
        Files.write(legacy, bytes);

        KnowledgeDocumentEntity doc = document(12L, "说明书.pdf", legacy);
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        UserWorkspaceStorageService storage = storage();
        KnowledgeFileMigrationService service =
                new KnowledgeFileMigrationService(repository, storage);

        Path migrated = service.ensureCanonicalFile(doc);

        assertThat(migrated)
                .isEqualTo(tempDir.resolve("users/4/knowledge/8/说明书.pdf"));
        assertThat(Files.readAllBytes(migrated)).containsExactly(bytes);
        assertThat(doc.getStoragePath()).isEqualTo(migrated.toAbsolutePath().toString());
        verify(repository).save(doc);
    }

    @Test
    void continuesMigratingAfterMissingLegacyFile() throws Exception {
        Path missing = tempDir.resolve("knowledge/4/doc_11.pdf");
        Path existing = tempDir.resolve("knowledge/4/doc_12.pdf");
        Files.createDirectories(existing.getParent());
        Files.write(existing, new byte[] {9, 8, 7});
        KnowledgeDocumentEntity first = document(11L, "missing.pdf", missing);
        KnowledgeDocumentEntity second = document(12L, "ready.pdf", existing);

        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        when(repository.findByUserIdOrderByIdAsc(4L)).thenReturn(List.of(first, second));
        KnowledgeFileMigrationService service =
                new KnowledgeFileMigrationService(repository, storage());

        List<Path> migrated = service.migrateUser(4L);

        assertThat(migrated).containsExactly(
                tempDir.resolve("users/4/knowledge/8/ready.pdf"));
    }

    private KnowledgeDocumentEntity document(Long id, String fileName, Path storagePath) {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(id);
        doc.setUserId(4L);
        doc.setKbId(8L);
        doc.setFileName(fileName);
        doc.setStoragePath(storagePath.toString());
        return doc;
    }

    private UserWorkspaceStorageService storage() {
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        return new UserWorkspaceStorageService(properties);
    }
}
