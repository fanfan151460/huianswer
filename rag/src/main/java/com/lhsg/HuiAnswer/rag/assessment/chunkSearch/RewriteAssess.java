package com.lhsg.HuiAnswer.rag.assessment.chunkSearch;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.assessment.req.RewriteAssessReq;
import com.lhsg.HuiAnswer.rag.assessment.resp.RewriteAssessResp;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.service.impl.SummaryServiceImpl;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.SubQuestionResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.process.SubQuestionProcessor;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import com.lhsg.HuiAnswer.rag.retrieval.rewrite.RewriteQuestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

import static com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum.CHAT;
import static com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum.CLARIFY;

@RestController
@RequiredArgsConstructor
@Slf4j
public class RewriteAssess {
    private final RewriteQuestion rewriteQuestion;
    private final SummaryServiceImpl summaryService;
    private final SubQuestionProcessor subQuestionProcessor;
    private final RerankChunks rerankChunks;

    @PostMapping("/api/evaluation/spilt")
    public RewriteAssessResp rewrite(@RequestBody RewriteAssessReq req) {
        String question = req.getQuestion();
        Long conversationId = req.getConversationId();
        Long userId = UsersContext.requireUserId();
        // 单轮拆分评测不需要创建会话；传了会话 ID 时仍按当前用户校验归属。
        SearchSummaryMemoryDTO searchSummaryMemoryDTO = conversationId == null
                ? new SearchSummaryMemoryDTO() : summaryService.summaryMemory(conversationId, userId);
        RewriteResultDTO rewriteResultDTO = rewriteQuestion.rewrite(question, searchSummaryMemoryDTO.getMemory());
        if (rewriteResultDTO.getSubQuestions().isEmpty()) {
            log.error("问题{}改写失败", question);
            throw new ServiceException("子问题集合为空，问题改写异常");
        }
        List<SubQuestionResultDTO> results = req.getChoose().equals("split") ?
                subQuestionProcessor.process(rewriteResultDTO.getSubQuestions())
                : subQuestionProcessor.process(List.of(rewriteResultDTO.getRewrittenQuestion()));
        for (SubQuestionResultDTO result : results) {
            if (result.isRouteFallback()) {
                log.error("子问题{}意图识别失败", result.getIndex());
                throw new ServiceException("子问题意图识别失败");
            }
        }
        List<Reference> references = mergeReferences(results);
        List<Reference> rerank = rerankChunks.rerank(req.getQuestion(), references);
        return new RewriteAssessResp().setRewriteResultDTO(rewriteResultDTO)
                .setReferences(rerank).setQuestion(question);
    }

    private List<Reference> mergeReferences(List<SubQuestionResultDTO> results) {
        Map<Long, Reference> merged = new HashMap<>();
        for (SubQuestionResultDTO result : results) {
            if (result.getIntent().equals(CHAT) || result.getIntent().equals(CLARIFY)) {
                continue;
            }
            for (Reference reference : result.getReferences()) {
                merged.putIfAbsent(reference.getChunkId(), reference);
            }
        }
        if (merged.isEmpty()) {
            return Collections.emptyList();
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(Reference::getScore).reversed())
                .collect(Collectors.toList());
    }

}
