package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.file.AioFileApi;
import com.example.sandbox.aio.shell.AioShellApi;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import com.example.sandbox.web.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void syncsKnowledgeAndUploadsAsBytes() throws Exception {
        AgentConfigProperties properties = new AgentConfigProperties();
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        UserWorkspaceStorageService storage = new UserWorkspaceStorageService(properties);
        byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46, 0x00, (byte) 0xFF};
        byte[] image = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        Files.createDirectories(storage.knowledgeFile(3L, 9L, "a.pdf").getParent());
        Files.write(storage.knowledgeFile(3L, 9L, "a.pdf"), pdf);
        Files.createDirectories(storage.uploadFile(3L, "b.png").getParent());
        Files.write(storage.uploadFile(3L, "b.png"), image);

        AioClient client = mock(AioClient.class);
        AioFileApi files = mock(AioFileApi.class);
        AioShellApi shell = mock(AioShellApi.class);
        ShellExecResult mkdirResult = new ShellExecResult();
        mkdirResult.setSuccess(true);
        ShellExecResult.ShellData mkdirData = new ShellExecResult.ShellData();
        mkdirData.setExitCode(0);
        mkdirResult.setData(mkdirData);
        when(client.files()).thenReturn(files);
        when(client.shell()).thenReturn(shell);
        when(shell.exec("mkdir -p '/home/gem/knowledge/9'")).thenReturn(mkdirResult);
        when(shell.exec("mkdir -p '/home/gem/uploads'")).thenReturn(mkdirResult);
        when(files.writeBytes("/home/gem/knowledge/9/a.pdf", pdf)).thenReturn(true);
        when(files.writeBytes("/home/gem/uploads/b.png", image)).thenReturn(true);

        FileSyncService service = new FileSyncService(storage);
        FileSyncService.SyncResult result = service.syncUserWorkspace(3L, client);

        assertThat(result.failedPaths()).isEmpty();
        assertThat(result.successCount()).isEqualTo(2);
        verify(shell).exec("mkdir -p '/home/gem/knowledge/9'");
        verify(shell).exec("mkdir -p '/home/gem/uploads'");
        verify(files).writeBytes("/home/gem/knowledge/9/a.pdf", pdf);
        verify(files).writeBytes("/home/gem/uploads/b.png", image);
    }
}
