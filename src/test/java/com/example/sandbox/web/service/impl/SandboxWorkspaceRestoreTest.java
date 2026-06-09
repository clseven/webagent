package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.SkillService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxWorkspaceRestoreTest {

    @Test
    void migratesBeforeSyncingUserWorkspace() {
        KnowledgeFileMigrationService migrationService =
                mock(KnowledgeFileMigrationService.class);
        FileSyncService fileSyncService = mock(FileSyncService.class);
        AioSandboxClient client = mock(AioSandboxClient.class);
        when(fileSyncService.syncUserWorkspace(7L, client))
                .thenReturn(new FileSyncService.SyncResult(2, java.util.List.of()));
        SandboxServiceImpl service = new SandboxServiceImpl(
                mock(SkillService.class),
                mock(ConversationSessionRepository.class),
                new AgentConfigProperties());
        ReflectionTestUtils.setField(service, "migrationService", migrationService);
        ReflectionTestUtils.setField(service, "fileSyncService", fileSyncService);

        service.restoreUserWorkspace(7L, "s1", client);

        var order = inOrder(migrationService, fileSyncService);
        order.verify(migrationService).migrateUser(7L);
        order.verify(fileSyncService).syncUserWorkspace(7L, client);
    }
}
