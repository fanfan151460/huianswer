package com.lhsg.HuiAnswer.rag.knowledge.service;

import java.util.List;

public interface IKnowledgeChunkService {
    /**
     * 文字转为分块（不含业务信息）
     * @param text 文档内容
     * @return chunk集合
     */
    List<String> toChunks(String text);

    void saveChunks(List<String> chunks, Long kbId, Long docId);
}
