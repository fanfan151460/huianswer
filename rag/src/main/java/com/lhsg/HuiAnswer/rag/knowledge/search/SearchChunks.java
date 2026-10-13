package com.lhsg.HuiAnswer.rag.knowledge.search;

import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embedding.EmbeddingChunks;
import com.lhsg.HuiAnswer.rag.knowledge.keyWordUtil.ToKeyWord;
import com.lhsg.HuiAnswer.rag.retrieval.dto.KeyWordChunks;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.VectorChunks;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合检索知识库并重排
 */
@Component
@RequiredArgsConstructor
public class SearchChunks {
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingChunks embeddingChunks;

    @Value("${rag.search.max-distance}")
    private double maxDistance;
    @Value("${rag.rrf-k}")
    private int rrfK;
    @Value("${rag.search.mix-search-count}")
    private int mixSearchCount;
    @Value("${rag.search.topK}")
    private int topK;

    /**
     * RRF 融合。kbIds 为 null 时检索全库；空集合不检索；否则只查选中的库。
     */
    @TimeAnnotation(method = "混合检索")
    public List<Reference> mixSearch(List<Long> kbIds, String question) {
        if (kbIds != null && kbIds.isEmpty()) {
            return List.of();
        }
        List<Reference> vectorHits = vectorSearch(kbIds, question).stream()
                .map(this::toReference)
                .toList();
        List<Reference> keywordHits = searchKeyWord(kbIds, question).stream()
                .map(this::toReference)
                .toList();

        // chunkId -> 片段，同时作为去重表；同一片段优先保留向量那一路的，在遇到
        Map<Long, Reference> merged = new LinkedHashMap<>();
        Map<Long, Double> scores = new HashMap<>();
        accumulate(vectorHits, merged, scores);
        accumulate(keywordHits, merged, scores);

        return merged.values().stream()
                .map(hit -> hit.setScore(scores.get(hit.getChunkId())))
                .sorted(Comparator.comparingDouble(Reference::getScore).reversed())
                .limit(mixSearchCount)
                .toList();
    }

    /**
     * 向量检索，按余弦距离升序，丢掉距离过大的片段
     */
    public List<VectorChunks> vectorSearch(List<Long> kbIds, String question) {
        if (kbIds != null && kbIds.isEmpty()) {
            return List.of();
        }
        float[] embedding = questionToVector(question);
        return chunkMapper.vectorSearch(kbIds, embedding, topK).stream()
                .filter(each -> each.getDistance() < maxDistance)
                .collect(Collectors.toList());
    }

    /**
     * 关键词检索，按 ts_rank_cd 降序。分词后没有可用词时不查库：
     * 返回空集合取向量查询tok
     */
    public List<KeyWordChunks> searchKeyWord(List<Long> kbIds, String question) {
        if (kbIds != null && kbIds.isEmpty()) {
            return List.of();
        }
        String keyword = ToKeyWord.toKeyQuestion(question);
        if (keyword.isEmpty()) {
            return List.of();
        }
        return chunkMapper.keySearch(kbIds, keyword, topK);
    }


    /**
     * 将一路结果整理为两个map存储资料和分数，当重复时分数map叠加、资料map不重复覆盖
     */
    private void accumulate(List<Reference> hits, Map<Long, Reference> merged, Map<Long, Double> scores) {
        for (int i = 0; i < hits.size(); i++) {
            Reference hit = hits.get(i);
            merged.putIfAbsent(hit.getChunkId(), hit);
            scores.merge(hit.getChunkId(), rrfScore(i), Double::sum);
        }
    }

    /**
     * index（下标）计算rrf分数
     */
    private double rrfScore(int index) {
        return 1.0 / (rrfK + index + 1);
    }

    private Reference toReference(VectorChunks hit) {
        return new Reference()
                .setDocName(hit.getDocName())
                .setDocId(hit.getDocId())
                .setContent(hit.getContent())
                .setChunkId(hit.getChunkId());
    }

    private Reference toReference(KeyWordChunks hit) {
        return new Reference()
                .setDocName(hit.getDocName())
                .setDocId(hit.getDocId())
                .setContent(hit.getContent())
                .setChunkId(hit.getChunkId());
    }

    private float[] questionToVector(String question) {
        List<String> list = Collections.singletonList(question);
        List<float[]> questionVector = embeddingChunks.doEmbedding(list);
        return questionVector.get(0);
    }
}
