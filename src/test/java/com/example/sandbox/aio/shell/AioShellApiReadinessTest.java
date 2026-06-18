package com.example.sandbox.aio.shell;

import com.example.sandbox.aio.core.AioHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 AIO Shell 就绪等待采用快速条件轮询，不包含固定启动休眠。
 */
class AioShellApiReadinessTest {

    /**
     * 首次探测失败后应在超时范围内继续探测，并在服务就绪后立即返回。
     */
    @Test
    void waitUntilReadyPollsAgainWithoutFixedGracePeriod() {
        AioHttpClient http = mock(AioHttpClient.class);
        when(http.getTextQuietly("/v1/shell/sessions", Duration.ofSeconds(5)))
                .thenThrow(new RuntimeException("尚未就绪"))
                .thenReturn("{}");
        AioShellApi api = new AioShellApi(http);

        long startedAt = System.nanoTime();
        boolean ready = api.waitUntilReady(Duration.ofSeconds(2));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(ready);
        assertTrue(elapsedMillis >= 900, "两次就绪探测之间应等待约一秒");
        verify(http, times(2)).getTextQuietly("/v1/shell/sessions", Duration.ofSeconds(5));
    }
}
