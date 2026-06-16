package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.browser.AioBrowserApi;
import com.example.sandbox.aio.node.AioNodeApi;
import com.example.sandbox.aio.node.model.NodeExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证 Browser Agent Java 包装层只调用沙箱内部运行时，不再注入宿主机 CDP 地址。
 */
class BrowserAgentRuntimeServiceTest {

    /**
     * 页面检查脚本应由沙箱内部运行时自行解析 CDP，并且不调用 browser/info。
     */
    @Test
    void inspectUsesInternalRuntimeCdpResolution() {
        AioClient client = mock(AioClient.class);
        AioNodeApi nodeApi = mock(AioNodeApi.class);
        AioBrowserApi browserApi = mock(AioBrowserApi.class);
        NodeExecuteResult executeResult = successfulResult("{\"title\":\"test\"}");
        when(client.node()).thenReturn(nodeApi);
        when(client.browser()).thenReturn(browserApi);
        when(nodeApi.execute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(30)))
                .thenReturn(executeResult);

        BrowserAgentRuntimeService service = new BrowserAgentRuntimeService(new ObjectMapper());
        String result = service.inspect(client, 80, 8000);

        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(nodeApi).execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.eq(30));
        String script = scriptCaptor.getValue();
        assertEquals("{\"title\":\"test\"}", result);
        assertTrue(script.contains("runtime.inspect({"));
        assertFalse(script.contains("cdp_url"));
        assertFalse(script.contains("connectOverCDP"));
        verifyNoInteractions(browserApi);
    }

    /**
     * 创建一份包含 Browser Agent 结果标记的成功 Node.js 响应。
     *
     * @param json 需要返回的 JSON 文本
     * @return 可供运行时服务解析的执行结果
     */
    private NodeExecuteResult successfulResult(String json) {
        NodeExecuteResult result = new NodeExecuteResult();
        result.setStatus("ok");
        result.setExitCode(0);
        result.setStdout("__BROWSER_AGENT_RESULT__" + json + "\n");
        return result;
    }
}
