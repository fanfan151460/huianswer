package com.lhsg.HuiAnswer.rag.assessment;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.assessment.chunkSearch.IntentAssess;
import com.lhsg.HuiAnswer.rag.assessment.req.IntentAssessReq;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.service.impl.SummaryServiceImpl;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.Identify;
import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import com.lhsg.HuiAnswer.rag.retrieval.rewrite.RewriteQuestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntentAssessHistoryTest {
    private final RewriteQuestion rewrite = mock(RewriteQuestion.class);
    private final SummaryServiceImpl summary = mock(SummaryServiceImpl.class);
    private final Identify identify = mock(Identify.class);
    private final IntentAssess controller = new IntentAssess(rewrite, summary, identify);
    private final List<SearchMemoryDTO> history = List.of(
            new SearchMemoryDTO().setRole("user").setContent("普通零售订单，尚未付款。"),
            new SearchMemoryDTO().setRole("assistant").setContent("请说明需要查询的内容。"),
            new SearchMemoryDTO().setRole("user").setContent("支付期限多长？"),
            new SearchMemoryDTO().setRole("assistant").setContent("这条已有答案不能提前送给改写。"));

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UsersContext.setUserDTO(user);
        when(summary.summaryMemory(300L, 1L)).thenReturn(new SearchSummaryMemoryDTO().setMemory(history));
        when(rewrite.rewrite(anyString(), anyList())).thenAnswer(call -> {
            String question = call.getArgument(0);
            return new RewriteResultDTO().setRewrittenQuestion(question).setSubQuestions(List.of(question));
        });
        when(identify.identifyOne(anyString(), anyInt(), anyList()))
                .thenReturn(new IntentResultDTO().setIntent(IntentEnum.KNOWLEDGE));
    }

    @AfterEach
    void clearUser() {
        UsersContext.removeUserDTO();
    }

    @Test
    void newQuestionUsesOnlyHistoryBeforeOriginalQuestion() {
        IntentAssessReq req = request("谢谢，明白了。");
        req.setHistoryBeforeQuestion("支付期限多长？");

        assertEquals(1, controller.intentAssess(req).size());

        verify(rewrite).rewrite("谢谢，明白了。", history.subList(0, 2));
        assertEquals(4, history.size(), "截取历史不能修改会话记录");
    }

    @Test
    void missingExplicitAnchorFailsBeforeCallingModels() {
        IntentAssessReq req = request("谢谢，明白了。");
        req.setHistoryBeforeQuestion("已经不在历史窗口里的原题");

        assertThrows(ServiceException.class, () -> controller.intentAssess(req));
        verifyNoInteractions(rewrite, identify);
    }

    @Test
    void oldReplayStillCutsHistoryAtCurrentQuestion() {
        controller.intentAssess(request("支付期限多长？"));

        verify(rewrite).rewrite("支付期限多长？", history.subList(0, 2));
    }

    private IntentAssessReq request(String question) {
        IntentAssessReq req = new IntentAssessReq();
        req.setConversationId(300L);
        req.setQuestion(question);
        return req;
    }
}
