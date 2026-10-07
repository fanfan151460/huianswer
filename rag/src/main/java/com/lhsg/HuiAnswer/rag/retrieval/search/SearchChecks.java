package com.lhsg.HuiAnswer.rag.retrieval.search;

import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil.EmbeddingChunks;
import com.lhsg.HuiAnswer.rag.retrieval.dto.References;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SearchChecks {
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingChunks embeddingChunks;

    public List<References> search(long kbId, String question, int topK) {
        float[] embedding = questionToVector(question);
        return chunkMapper.search(kbId, embedding, topK);
    }

    public float[] questionToVector(String question) {
        List<String> list = Collections.singletonList(question);
        List<float[]> questionVector = embeddingChunks.doEmbedding(list);
        return questionVector.get(0);
    }
}
