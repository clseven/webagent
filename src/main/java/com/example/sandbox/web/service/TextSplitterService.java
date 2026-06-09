package com.example.sandbox.web.service;

import java.util.List;

/**
 * 文本切片服务接口
 *
 * @author example
 * @date 2026/05/31
 */
public interface TextSplitterService {

    /**
     * 智能切片（按段落自然切分）
     *
     * @param text 待切分文本
     * @return 切片列表
     */
    List<String> splitSmart(String text);

    /**
     * 自定义切片（按字符数 + 重叠）
     *
     * @param text      待切分文本
     * @param chunkSize 每个切片的目标字符数
     * @param overlap   重叠字符数
     * @return 切片列表
     */
    List<String> splitCustom(String text, int chunkSize, int overlap);

    /**
     * 智能切片（带原文位置信息）
     * <p>用于文件预览时切片与原文位置联动</p>
     *
     * @param text 待切分文本
     * @return 带位置的切片列表
     */
    List<ChunkWithPosition> splitSmartWithPosition(String text);

    /**
     * 自定义切片（带原文位置信息）
     *
     * @param text      待切分文本
     * @param chunkSize 每个切片的目标字符数
     * @param overlap   重叠字符数
     * @return 带位置的切片列表
     */
    List<ChunkWithPosition> splitCustomWithPosition(String text, int chunkSize, int overlap);

    /**
     * 带原文位置信息的切片
     */
    class ChunkWithPosition {
        private final String content;
        private final int startOffset;
        private final int endOffset;

        public ChunkWithPosition(String content, int startOffset, int endOffset) {
            this.content = content;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public String getContent() { return content; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }

        public int length() { return endOffset - startOffset; }
    }
}
