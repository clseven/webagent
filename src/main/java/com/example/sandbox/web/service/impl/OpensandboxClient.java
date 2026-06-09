package com.example.sandbox.web.service.impl;

//import com.example.sandbox.web.model.entity.ExecutionResult;
//import com.example.sandbox.web.service.SandboxClient;
//import com.example.sandbox.web.service.SandboxService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.time.Instant;

/**
 * @deprecated 已废弃，统一使用 AioSandboxClient
 *
 * <p>Opensandbox 沙箱客户端实现</p>
 * <p>封装 opensandbox SDK 的操作，统一 API 接口。</p>
 *
 * @author example
 * @date 2026/05/20
 */
//@Component
//public class OpensandboxClient implements SandboxClient {
//
//    @Autowired
//    private SandboxService sandboxService;
//
//    private String sessionId;
//
//    public void setSessionId(String sessionId) {
//        this.sessionId = sessionId;
//    }
//
//    public String getSessionId() {
//        return sessionId;
//    }
//
//    @Override
//    public String execCommand(String command) {
//        ExecutionResult result = sandboxService.executeCommand(sessionId, command);
//        return result.getBody();
//    }
//
//    @Override
//    public String readFile(String path) {
//        ExecutionResult result = sandboxService.readFile(sessionId, path);
//        return result.isSuccess() ? result.getBody() : "读取失败：" + result.getBody();
//    }
//
//    @Override
//    public void writeFile(String path, String content) {
//        ExecutionResult result = sandboxService.writeFile(sessionId, path, content);
//        if (!result.isSuccess()) {
//            throw new RuntimeException("写入失败：" + result.getBody());
//        }
//    }
//
//    @Override
//    public byte[] downloadFile(String path) {
//        // opensandbox SDK 不支持直接下载文件，返回 null
//        return null;
//    }
//
//    @Override
//    public byte[] screenshot() {
//        // COMMON 沙箱不支持截图
//        return null;
//    }
//
//    @Override
//    public SandboxContext getContext() {
//        // COMMON 沙箱不支持获取环境信息
//        return null;
//    }
//
//    @Override
//    public boolean isReady() {
//        return sandboxService.hasSandbox(sessionId);
//    }
//}
