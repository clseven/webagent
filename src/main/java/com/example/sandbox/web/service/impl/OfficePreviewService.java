package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.KnowledgeDocumentEntity;
import com.example.sandbox.web.model.response.FilePreviewContent;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class OfficePreviewService {

    private static final Set<String> OFFICE_EXTENSIONS = Set.of(
            "doc", "docx", "odt", "rtf",
            "xls", "xlsx", "ods",
            "ppt", "pptx", "odp");

    private final RagConfigProperties properties;

    public OfficePreviewService(RagConfigProperties properties) {
        this.properties = properties;
    }

    public boolean isConvertible(String fileName) {
        return OFFICE_EXTENSIONS.contains(extension(fileName));
    }

    public FilePreviewContent previewKnowledge(AioClient client,
                                               KnowledgeDocumentEntity document,
                                               String sandboxPath) {
        requireConvertible(sandboxPath);
        String sourceHash = sourceHash(client, sandboxPath);
        String cachePath = "/home/gem/knowledge/" + document.getKbId()
                + "/.preview/" + document.getId() + "-" + sourceHash + ".pdf";
        return preview(client, sandboxPath, cachePath, document.getFileName());
    }

    public FilePreviewContent previewWorkspace(AioClient client, String sandboxPath) {
        requireConvertible(sandboxPath);
        String sourceHash = sourceHash(client, sandboxPath);
        String pathHash = sha256(sandboxPath).substring(0, 24);
        String cachePath = "/home/gem/temp/previews/"
                + pathHash + "-" + sourceHash + ".pdf";
        return preview(client, sandboxPath, cachePath, fileName(sandboxPath));
    }

    private FilePreviewContent preview(AioClient client,
                                       String sourcePath,
                                       String cachePath,
                                       String originalFileName) {
        if (!client.shell().fileExists(cachePath)) {
            convert(client, sourcePath, cachePath);
        }
        byte[] content = client.files().download(cachePath);
        if (content == null || content.length == 0) {
            throw new RuntimeException("Office 预览文件为空: " + cachePath);
        }
        return new FilePreviewContent(
                content, "application/pdf", "pdf", originalFileName);
    }

    private void convert(AioClient client, String sourcePath, String cachePath) {
        String conversionId = UUID.randomUUID().toString();
        String outputDir = "/home/gem/temp/previews/convert-" + conversionId;
        String profile = "file:///tmp/lo-profile-" + conversionId;
        String generatedPdf = outputDir + "/" + baseName(sourcePath) + ".pdf";
        String cacheDir = parentPath(cachePath);
        int timeout = properties.getPreview().getConversion().getTimeoutSeconds();
        String command = "mkdir -p " + quote(cacheDir) + " " + quote(outputDir)
                + " && timeout " + timeout + " soffice"
                + " --headless --nologo --nofirststartwizard --norestore"
                + " -env:UserInstallation=" + profile
                + " --convert-to pdf"
                + " --outdir " + quote(outputDir)
                + " " + quote(sourcePath)
                + " && mv " + quote(generatedPdf) + " " + quote(cachePath)
                + " ; status=$?; rm -rf " + quote(outputDir)
                + "; exit $status";
        client.shell().exec(command);
        if (!client.shell().fileExists(cachePath)) {
            throw new RuntimeException("LibreOffice 转换失败或超时: " + sourcePath);
        }
    }

    private String sourceHash(AioClient client, String sourcePath) {
        String output = client.shell().exec("sha256sum -- " + quote(sourcePath)).getOutput();
        if (output == null || output.isBlank()) {
            throw new RuntimeException("无法计算预览源文件版本: " + sourcePath);
        }
        String hash = output.trim().split("\\s+")[0];
        if (!hash.matches("[A-Za-z0-9_-]+")) {
            throw new RuntimeException("无效的源文件哈希: " + hash);
        }
        return hash;
    }

    private void requireConvertible(String path) {
        requireHomeGemPath(path);
        if (!properties.getPreview().getConversion().isEnabled()) {
            throw new RuntimeException("Office 预览转换已禁用");
        }
        if (!isConvertible(path)) {
            throw new IllegalArgumentException("不支持转换的文件类型: " + path);
        }
    }

    private String requireHomeGemPath(String path) {
        if (path == null || !path.startsWith("/home/gem/") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("不允许预览该路径");
        }
        for (String segment : path.substring("/home/gem/".length()).split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("不允许预览该路径");
            }
        }
        return path;
    }

    private String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String baseName(String path) {
        String name = fileName(path);
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(0, index) : name;
    }

    private String parentPath(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    private String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
