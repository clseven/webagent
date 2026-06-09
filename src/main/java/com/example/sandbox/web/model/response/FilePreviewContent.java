package com.example.sandbox.web.model.response;

public record FilePreviewContent(
        byte[] content,
        String mediaType,
        String previewType,
        String originalFileName
) {
}
