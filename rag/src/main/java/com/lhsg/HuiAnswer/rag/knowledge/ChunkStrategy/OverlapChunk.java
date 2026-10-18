package com.lhsg.HuiAnswer.rag.knowledge.ChunkStrategy;

import cn.hutool.core.util.StrUtil;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class OverlapChunk {

    public static List<String> toChunks(String text, int chunkSize, int overlap) {
        if (chunkSize <= overlap) {
            log.error("分块出错，原因：chunkSize：{} <= overlap：{}", chunkSize, overlap);
            throw new ServiceException("分块出错，请尝试更换其它策略");
        }
        if (StrUtil.isBlank(text)) {
            throw new ClientException("文档内容为空");
        }
        // 按 Unicode 字符计数，避免 substring 把 emoji 的 UTF-16 代理对切开。
        int[] characters = text.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < characters.length) {
            int end = Math.min(start + chunkSize, characters.length);
            String content = new String(characters, start, end - start);
            if (!content.isBlank()) {
                chunks.add(content);
            }
            // 到达全文末尾即结束；最后不足一块也保留，不再单独生成重叠尾块。
            if (end == characters.length) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }

}
