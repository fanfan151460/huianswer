package com.lhsg.HuiAnswer.rag.assessment.chunkSearch;

import cn.hutool.core.bean.BeanUtil;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.assessment.req.ChunkAssessReq;
import com.lhsg.HuiAnswer.rag.assessment.resp.AssessChunk;
import com.lhsg.HuiAnswer.rag.assessment.resp.AssessChunkResp;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChunks;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class ChunkAssess {

    private final SearchChunks searchChunks;
    private final RerankChunks rerankChunks;

    @PostMapping("/api/evaluation/retrieval")
    public AssessChunkResp vectorAssess(@RequestBody ChunkAssessReq chunkAssessReq) {
        long start = System.nanoTime();
        List<AssessChunk> assessList;
        assessList = switch (chunkAssessReq.getChoose()) {
            case "vector" -> searchChunks.vectorSearch(chunkAssessReq.getDbIds(), chunkAssessReq.getQuestion()).stream()
                    .map(each -> BeanUtil.copyProperties(each, AssessChunk.class).setScore(each.getDistance()))
                    .collect(Collectors.toList());
            case "searchKeyWord" -> searchChunks.searchKeyWord(chunkAssessReq.getDbIds(), chunkAssessReq.getQuestion()).stream()
                    .map(each -> BeanUtil.copyProperties(each, AssessChunk.class).setScore(each.getKeywordScore()))
                    .collect(Collectors.toList());
            case "", "mixSearch" -> searchChunks.mixSearch(chunkAssessReq.getDbIds(), chunkAssessReq.getQuestion()).stream()
                    .map(each -> BeanUtil.copyProperties(each, AssessChunk.class).setScore(each.getScore()))
                    .collect(Collectors.toList());
            case "rerank" -> rerankChunks.rerank(chunkAssessReq.getQuestion(), searchChunks.mixSearch(chunkAssessReq.getDbIds(), chunkAssessReq.getQuestion()))
                    .stream()
                    .map(each -> BeanUtil.copyProperties(each, AssessChunk.class).setScore(each.getRerankScore()))
                    .collect(Collectors.toList());
            default -> throw new ClientException("策略参数有误");
        };
        return new AssessChunkResp().setChunks(assessList).setTime(System.nanoTime() - start);
    }
}
