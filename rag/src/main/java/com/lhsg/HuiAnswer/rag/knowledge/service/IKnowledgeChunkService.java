package com.lhsg.HuiAnswer.rag.knowledge.service;

import java.util.List;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;

public interface IKnowledgeChunkService {
    /**
     * 文字转为分块（不含业务信息）
     * @param text 文档内容
     * @return chunk集合
     */
    List<String> toChunks(String text);

    /** 调用向量接口并配对原文；不写数据库。 */
    List<KnowledgeChunkDO> prepareChunks(List<String> chunks, Long kbId, Long docId);

    /** 只批量写库，在最终短事务中调用。 */
    void saveChunks(List<KnowledgeChunkDO> chunks);
}
