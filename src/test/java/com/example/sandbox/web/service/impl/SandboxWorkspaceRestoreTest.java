package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.SkillService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证用户工作空间恢复顺序和沙箱生命周期恢复行为。
 */
class SandboxWorkspaceRestoreTest {

    /**
     * 用户文件恢复前应先完成知识文件迁移。
     */
    @Test
    void migratesBeforeSyncingUserWorkspace() {
        KnowledgeFileMigrationService migrationService =
                mock(KnowledgeFileMigrationService.class);
        FileSyncService fileSyncService = mock(FileSyncService.class);
        AioClient client = mock(AioClient.class);
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

    /**
     * AIO 模式下定时任务应按 sandboxId 重新连接，并使用配置的 P1D 续期。
     */
    @Test
    @SuppressWarnings("unchecked")
    void reconnectsAndRenewsAioSandboxAfterRestart() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setImage("agent-infra/sandbox-office:latest");
        config.getSandbox().setSandboxTimeout("P1D");
        SandboxAgent agent = mock(SandboxAgent.class);
        when(agent.isHealthy()).thenReturn(true);
        when(agent.renew(Duration.ofDays(1))).thenReturn(OffsetDateTime.parse("2026-07-17T12:00:00Z"));

        SandboxServiceImpl service = new SandboxServiceImpl(
                mock(SkillService.class),
                mock(ConversationSessionRepository.class),
                config) {
            /**
             * 测试中返回可控代理，避免连接真实 OpenSandbox 服务。
             *
             * @param sandboxId 已有沙箱 ID
             * @return 模拟沙箱代理
             */
            @Override
            protected SandboxAgent connectSandboxAgent(String sandboxId) {
                return agent;
            }
        };

        Map<Long, String> userSandboxMap =
                (Map<Long, String>) ReflectionTestUtils.getField(service, "userSandboxMap");
        Map<String, SandboxAgent> sandboxAgents =
                (Map<String, SandboxAgent>) ReflectionTestUtils.getField(service, "sandboxAgents");
        userSandboxMap.put(7L, "sandbox-7");

        service.renewAllSandboxes();

        verify(agent).renew(Duration.ofDays(1));
        assertSame(agent, sandboxAgents.get("sandbox-7"));
    }
}
