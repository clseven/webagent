package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * OSS 文件存储服务实现（预留）
 * 生产环境切换时，配置 storage.type=oss 即可启用
 *
 * @author example
 * @date 2026/05/20
 */
@Service
@ConditionalOnProperty(name = "agent.storage.type", havingValue = "oss")
public class OssFileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(OssFileStorageServiceImpl.class);

    private static final String MOUNT_PATH = "/home/gem/uploads";

    @Autowired
    private AgentConfigProperties config;

    @Override
    public String store(String sessionId, String originalFilename, InputStream inputStream) {
        // TODO: 实现阿里云 OSS 上传
        // AgentConfigProperties.Storage.Oss oss = config.getStorage().getOss();
        // OSS ossClient = new OSSClientBuilder().build(oss.getEndpoint(), oss.getAccessKey(), oss.getSecretKey());
        // ossClient.putObject(oss.getBucket(), sessionId + "/" + originalFilename, inputStream);
        throw new UnsupportedOperationException("OSS 存储暂未实现，请使用 local 类型");
    }

    @Override
    public String store(String sessionId, String originalFilename, byte[] data) {
        throw new UnsupportedOperationException("OSS 存储暂未实现，请使用 local 类型");
    }

    @Override
    public InputStream getFile(String sessionId, String filename) {
        throw new UnsupportedOperationException("OSS 存储暂未实现，请使用 local 类型");
    }

    @Override
    public void delete(String sessionId, String filename) {
        throw new UnsupportedOperationException("OSS 存储暂未实现，请使用 local 类型");
    }

    @Override
    public void deleteAll(String sessionId) {
        throw new UnsupportedOperationException("OSS 存储暂未实现，请使用 local 类型");
    }

    @Override
    public String getStoragePath(String sessionId) {
        // OSS 无法直接挂载到沙盒，需要结合 NAS 或其他方案
        throw new UnsupportedOperationException("OSS 存储暂未实现，请使用 local 类型");
    }

    @Override
    public String getMountPath() {
        return MOUNT_PATH;
    }

    @Override
    public String getType() {
        return "oss";
    }
}
