package com.lhsg.HuiAnswer.rag.knowledge.ChunkStrategy;

import cn.hutool.core.util.StrUtil;
import com.lhsg.HuiAnswer.framework.exception.ClientException;

import java.util.ArrayList;
import java.util.List;

public final class RecursionChunk {
    // 从段落到句子逐层细分；空字符串表示最后按 Unicode 字符硬切。
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "！", "？", "；", "，", " ", ""};

    private RecursionChunk() {
    }

    public static List<String> recursionChunk(String text, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("递归分块大小必须大于0");
        }
        if (StrUtil.isBlank(text)) {
            throw new ClientException("文档内容为空");
        }
        List<String> pieces = new ArrayList<>();
        split(text, chunkSize, 0, pieces);
        return merge(pieces, chunkSize);
    }

    /** 只对超长片段继续使用下一种分隔符，符合大小的片段直接保留。 */
    private static void split(String text, int chunkSize, int level, List<String> pieces) {
        if (text.codePointCount(0, text.length()) <= chunkSize) {
            pieces.add(text);
            return;
        }
        String separator = SEPARATORS[level];
        if (separator.isEmpty()) {
            int[] characters = text.codePoints().toArray();
            for (int start = 0; start < characters.length; start += chunkSize) {
                int length = Math.min(chunkSize, characters.length - start);
                pieces.add(new String(characters, start, length));
            }
            return;
        }
        int start = 0;
        int position;
        while ((position = text.indexOf(separator, start)) >= 0) {
            // 把分隔符留在前一段末尾，避免句号、逗号、换行丢失。
            int end = position + separator.length();
            split(text.substring(start, end), chunkSize, level + 1, pieces);
            start = end;
        }
        if (start < text.length()) {
            // 当前分隔符不存在时，整段文本会进入下一层，尝试更细的分隔符。
            split(text.substring(start), chunkSize, level + 1, pieces);
        }
    }

    /** 按原文顺序合并小片段，分隔符也计入块大小；本策略不额外添加重叠。 */
    private static List<String> merge(List<String> pieces, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentSize = 0;
        for (String piece : pieces) {
            int pieceSize = piece.codePointCount(0, piece.length());
            if (currentSize + pieceSize > chunkSize) {
                addChunk(chunks, current);
                current.setLength(0);
                currentSize = 0;
            }
            current.append(piece);
            currentSize += pieceSize;
        }
        addChunk(chunks, current);
        return chunks;
    }

    private static void addChunk(List<String> chunks, StringBuilder current) {
        String content = current.toString();
        // 连续大量空行可能单独成块，不把这种没有文字的块送去向量化。
        if (!content.isBlank()) {
            chunks.add(content);
        }
    }
}
