package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.service.TextSplitterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片服务实现
 *
 * 智能模式：按段落自然切分，超长段落按自默认参数二次切分
 * 自定义模式：按指定字符数切分，支持重叠
 *
 * @author example
 * @date 2026/05/31
 */
@Service
public class TextSplitterServiceImpl implements TextSplitterService {

    private static final Logger log = LoggerFactory.getLogger(TextSplitterServiceImpl.class);

    /** 智能模式下，段落超过此长度则二次切分 */
    private static final int SMART_MAX_LENGTH = 1500;
    /** 智能模式下，二次切分的 chunkSize（500 tokens × 1.5 = 750 字符） */
    private static final int SMART_CHUNK_SIZE = 750;
    /** 智能模式下，二次切分的 overlap（50 tokens × 1.5 = 75 字符） */
    private static final int SMART_OVERLAP = 75;

    /**
     * 智能切片：按段落自然切分
     * - 段落 <= 2000 字符：保留完整
     * - 段落 > 2000 字符：按自定义默认参数（1500/200）二次切分
     */
    @Override
    public List<String> splitSmart(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // 按双换行切分为段落
        String[] paragraphs = text.split("\\n\\n+");

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) {
                continue;
            }

            if (para.length() > SMART_MAX_LENGTH) {
                // 超长段落，按自定义默认参数二次切分
                List<String> subChunks = splitCustom(para, SMART_CHUNK_SIZE, SMART_OVERLAP);
                chunks.addAll(subChunks);
            } else {
                // 正常段落，直接保留
                chunks.add(para);
            }
        }

        log.info("智能切片完成: 文本长度={}, 切片数={}", text.length(), chunks.size());
        return chunks;
    }

    /**
     * 自定义切片：按指定字符数切分，支持重叠
     */
    @Override
    public List<String> splitCustom(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // 先按段落切分
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) {
                continue;
            }

            String candidate = currentChunk.isEmpty() ? para : currentChunk + "\n\n" + para;

            if (candidate.length() > chunkSize) {
                // 当前 chunk 已满，先保存
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    // 保留 overlap
                    String overlapText = getOverlapText(currentChunk.toString(), overlap);
                    currentChunk = new StringBuilder(overlapText);
                }

                // 段落本身太长，需要进一步切分
                if (para.length() > chunkSize) {
                    List<String> subChunks = splitLongText(para, chunkSize, overlap);
                    chunks.addAll(subChunks);
                    currentChunk = new StringBuilder();
                } else {
                    currentChunk.append(para);
                }
            } else {
                if (!currentChunk.isEmpty()) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(para);
            }
        }

        // 最后剩余的 chunk
        if (!currentChunk.isEmpty()) {
            String lastChunk = currentChunk.toString().trim();
            if (!lastChunk.isEmpty()) {
                chunks.add(lastChunk);
            }
        }

        log.info("自定义切片完成: 文本长度={}, chunkSize={}, overlap={}, 切片数={}",
                text.length(), chunkSize, overlap, chunks.size());
        return chunks;
    }

    /**
     * 切分超长文本（段落级别切分失败时的兜底）
     * 按句号、问号、感叹号等自然断句处切分
     */
    private List<String> splitLongText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        // 按中英文句号、问号、感叹号切分
        String[] sentences = text.split("(?<=[。！？.!?])\\s*");
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.isBlank()) {
                continue;
            }

            String candidate = currentChunk.isEmpty() ? sentence : currentChunk + " " + sentence;

            if (candidate.length() > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString().trim());
                // 保留 overlap
                String overlapText = getOverlapText(currentChunk.toString(), overlap);
                currentChunk = new StringBuilder(overlapText);
            }

            currentChunk.append(currentChunk.isEmpty() ? sentence : " " + sentence);
        }

        if (!currentChunk.isEmpty()) {
            String lastChunk = currentChunk.toString().trim();
            if (!lastChunk.isEmpty()) {
                chunks.add(lastChunk);
            }
        }

        return chunks;
    }

    /**
     * 获取文本末尾的 overlap 部分
     */
    private String getOverlapText(String text, int overlap) {
        if (text.length() <= overlap) {
            return text;
        }
        return text.substring(text.length() - overlap);
    }

    // ========== 带位置信息的切片方法 ==========

    /**
     * 智能切片（带位置）：复用旧方法后用 indexOf 标注原文位置
     */
    @Override
    public List<ChunkWithPosition> splitSmartWithPosition(String text) {
        List<String> chunks = splitSmart(text);
        return attachPositions(text, chunks);
    }

    /**
     * 自定义切片（带位置）
     */
    @Override
    public List<ChunkWithPosition> splitCustomWithPosition(String text, int chunkSize, int overlap) {
        List<String> chunks = splitCustom(text, chunkSize, overlap);
        return attachPositions(text, chunks);
    }

    /**
     * 为 chunk 列表标注在原文中的位置
     * <p>策略：在原文中 indexOf 搜索 chunk 字符串，找不到时按累积偏移 fallback</p>
     */
    private List<ChunkWithPosition> attachPositions(String text, List<String> chunks) {
        List<ChunkWithPosition> result = new ArrayList<>();
        if (text == null) return result;

        int searchStart = 0;
        for (String chunk : chunks) {
            int start;
            int end;
            int found = text.indexOf(chunk, searchStart);
            if (found >= 0) {
                start = found;
                end = found + chunk.length();
            } else {
                // fallback: 累加偏移（兜底，保证 endOffset - startOffset = chunk.length()）
                start = searchStart;
                end = start + chunk.length();
            }
            result.add(new ChunkWithPosition(chunk, start, end));
            searchStart = end;
        }
        return result;
    }
}
