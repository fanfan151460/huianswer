package com.lhsg.HuiAnswer.rag.assessment.chunkSearch;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.assessment.req.IntentAssessReq;
import com.lhsg.HuiAnswer.rag.assessment.resp.IntentAssessResp;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.service.impl.SummaryServiceImpl;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.Identify;
import com.lhsg.HuiAnswer.rag.retrieval.rewrite.RewriteQuestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class IntentAssess {
    private final RewriteQuestion rewriteQuestion;
    private final SummaryServiceImpl summaryService;
    private final Identify identify;

    @RequestMapping("/api/evaluation/intent")
    public List<IntentAssessResp> intentAssess(IntentAssessReq req) {
        List<IntentAssessResp> IntentAssessList = new ArrayList<>();
        Long userId = UsersContext.requireUserId();
        SearchSummaryMemoryDTO searchSummaryMemoryDTO = summaryService.summaryMemory(req.getConversationId(), userId);
        List<SearchMemoryDTO> memory = searchSummaryMemoryDTO.getMemory();
        // 复测旧会话时，排除本题及之后的问答，避免改写提前读到本题答案。
        int beforeIndex = memory.size();
        for (int i = 0; i < memory.size(); i++) {
            if ("user".equals(memory.get(i).getRole())
                    && req.getQuestion().equals(memory.get(i).getContent())) {
                beforeIndex = i;
            }
        }
        memory = memory.subList(0, beforeIndex);
        RewriteResultDTO rewrite = rewriteQuestion.rewrite(req.getQuestion(), memory);
        if (rewrite.isFallback()) {
            throw new ServiceException("评测问题改写失败：" + rewrite.getErrorType());
        }
        List<String> subQuestions = rewrite.getSubQuestions();
        for (int i = 0; i < subQuestions.size(); i++) {
            String oneQuestion = subQuestions.get(i);
            IntentResultDTO intentResultDTO = identify.identifyOne(oneQuestion, i, identify.loadCatalog());
            if (intentResultDTO.isFallback()) {
                throw new ServiceException("评测意图识别失败：" + intentResultDTO.getErrorType());
            }
            IntentAssessResp resp = new IntentAssessResp();
            resp.setQuestion(req.getQuestion())
                    .setRewrite(rewrite)
                    .setIntent(intentResultDTO.getIntent().message());
            IntentAssessList.add(resp);
        }
        log.debug("问题重写为：{}, 子问题：{}", IntentAssessList.get(0).getRewrite().getRewrittenQuestion(), IntentAssessList.get(0).getRewrite().getRewrittenQuestion());
        return IntentAssessList;
    }
}
