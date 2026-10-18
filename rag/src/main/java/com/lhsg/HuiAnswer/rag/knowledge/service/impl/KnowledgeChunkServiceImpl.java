package com.lhsg.HuiAnswer.rag.knowledge.service.impl;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.ChunkStrategy.OverlapChunk;
import com.lhsg.HuiAnswer.rag.knowledge.ChunkStrategy.RecursionChunk;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embedding.EmbeddingChunks;
import com.lhsg.HuiAnswer.rag.knowledge.enums.SplitStrategy;
import com.lhsg.HuiAnswer.rag.knowledge.keyWordUtil.ToKeyWord;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements IKnowledgeChunkService {

    @Value("${rag.chunk.chunk-size}")
    private int chunkSize;
    @Value("${rag.chunk.OVERLAP}")
    private int overlap;
    @Value("${rag.chunk.recursion}")
    private int recursionChunkSize;
    private static final int INSERT_BATCH_SIZE = 100;
    private final EmbeddingChunks embeddingChunks;
    private final KnowledgeChunkMapper chunkMapper;

    @PostConstruct
    private void validateConfiguration() {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("分块大小必须大于0，重叠必须满足0 <= overlap < chunkSize");
        }
        if (recursionChunkSize <= 0) {
            throw new IllegalArgumentException("递归分块大小必须大于0");
        }
    }

    /**
     * 文档文本切块
     *
     * @param text 文档内容
     * @return 分块集合
     */
    @Override
    public List<String> toChunks(String text, SplitStrategy strategy) {
        // 兼容没有携带策略的旧消息，继续使用原来的滑动重叠策略。
        if (strategy == null) {
            strategy = SplitStrategy.OVERLAP;
        }
        return switch (strategy) {
            case OVERLAP -> OverlapChunk.toChunks(text, chunkSize, overlap);
            case RECURSIVE -> RecursionChunk.recursionChunk(text, recursionChunkSize);
        };
    }

    @Override
    public List<KnowledgeChunkDO> prepareChunks(List<String> chunks, Long kbId, Long docId) {
        List<float[]> embeddingList = embeddingChunks.doEmbedding(chunks);
        if (embeddingList == null || embeddingList.size() != chunks.size()) {
            throw new ServiceException("返回的向量数量与片段数量不一致");
        }
        List<KnowledgeChunkDO> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkDO chunk = new KnowledgeChunkDO().setChunkIndex(i)
                    .setContent(chunks.get(i)).setEmbedding(embeddingList.get(i))
                    .setSearchText(ToKeyWord.toKeyWord(chunks.get(i))) // 关键词入库
                    .setKbId(kbId).setDocId(docId);
            records.add(chunk);
        }
        return records;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveChunks(List<KnowledgeChunkDO> records) {
        chunkMapper.insert(records, INSERT_BATCH_SIZE);
    }
}