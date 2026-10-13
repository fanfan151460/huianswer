package com.lhsg.HuiAnswer.rag.chat.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.chat.dto.req.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.dto.resp.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.memory.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.service.ISummaryService;
import com.lhsg.HuiAnswer.rag.memory.dto.req.StoreDialogueDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.SubQuestionResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import com.lhsg.HuiAnswer.rag.retrieval.process.SubQuestionProcessor;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import com.lhsg.HuiAnswer.rag.retrieval.rewrite.RewriteQuestion;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceIntentTest {
    private final SubQuestionProcessor processor = mock(SubQuestionProcessor.class);
    private final RerankChunks rerank = mock(RerankChunks.class);
    private final RewriteQuestion rewrite = mock(RewriteQuestion.class);
    private final IMessageService messages = mock(IMessageService.class);
    private final ISummaryService summary = mock(ISummaryService.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final Gson gson = new Gson();
    private final AtomicInteger finalModelCalls = new AtomicInteger();
    private final AtomicReference<JsonObject> modelRequest = new AtomicReference<>();
    private int modelStatus = 200;
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(messages.saveQuestion(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(summary.summaryMemory(anyLong(), anyLong())).thenReturn(new SearchSummaryMemoryDTO());
        when(rerank.rerank(anyString(), anyList())).thenAnswer(call ->
                call.<List<Reference>>getArgument(1).stream().limit(5).toList());
        // 只模拟最终生成，所有请求由内存拦截器返回，不访问模型服务。
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            finalModelCalls.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            modelRequest.set(gson.fromJson(buffer.readUtf8(), JsonObject.class));
            String sse = """
                    data: {"model":"test-model","choices":[{"delta":{"content":"测试回答"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """;
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(modelStatus).message("test-response")
                    .body(ResponseBody.create(sse, MediaType.get("text/event-stream"))).build();
        }).build();
        ApiUrl api = new ApiUrl();
        api.setUrl("http://chat.test");
        api.setChatPath("/v1/chat/completions");
        service = new ChatServiceImpl(processor, rerank, rewrite, client, gson, messages, redis,
                mock(IConversationService.class), summary, api, mock(ThreadPoolExecutor.class));
        ReflectionTestUtils.setField(service, "chatModel", "test-model");
        ReflectionTestUtils.setField(service, "apiKey", "fake-key-for-local-test");
        ReflectionTestUtils.setField(service, "modelWait", 10L);
        ReflectionTestUtils.setField(service, "answerMaxTokens", 512);
        ReflectionTestUtils.setField(service, "answerTemperature", 0.0);
    }

    @Test
    void mixedIntentsGenerateOnceUsingSharedEvidenceAndOriginalQuestion() {
        List<String> questions = List.of("你好", "这个怎么处理", "会议室预约", "设备报修");
        prepare(questions, List.of(
                result(0, "你好", IntentEnum.CHAT, List.of()),
                result(1, "这个怎么处理", IntentEnum.CLARIFY, List.of()),
                result(2, "会议室预约", IntentEnum.KNOWLEDGE, List.of(reference(11))),
                result(3, "设备报修", IntentEnum.NO_MATCH, List.of(reference(11), reference(12)))));
        String original = "你好，这个怎么处理，还有会议室预约和设备报修";

        service.chatStream(request(original), emitter, 9L);

        verify(processor, times(1)).process(questions);
        verify(rerank, times(1)).rerank(eq(String.join("；", questions)), anyList());
        verifyNoMoreInteractions(rerank);
        verify(messages).saveQuestion(1L, 9L, original);
        assertEquals(1, finalModelCalls.get());
        assertOriginalQuestionLast(original);
        assertTrue(modelRequest.get().get("stream").getAsBoolean());
        assertNoProcessingHints();
        String context = messageStartingWith("【参考资料】");
        assertEquals(1, occurrences(context, "测试资料11"));
        assertEquals(1, occurrences(context, "测试资料12"));
        StoreDialogueDTO saved = savedAnswer();
        assertEquals("success", saved.getStatus());
        assertEquals("测试回答", saved.getContent());
        assertEquals(List.of(11L, 12L), saved.getSources().stream().map(NeedChunksResp::getChunkId).toList());
        verify(lock).unlock();
    }

    @Test
    void chatAndClarificationStillUseFinalGenerationWithoutRetrievalContext() {
        prepare(List.of("你好", "这个怎么办"), List.of(
                result(0, "你好", IntentEnum.CHAT, List.of()),
                result(1, "这个怎么办", IntentEnum.CLARIFY, List.of())));

        service.chatStream(request("你好，这个怎么办"), emitter, 9L);

        assertEquals(1, finalModelCalls.get());
        assertNoProcessingHints();
        verifyNoInteractions(rerank);
        assertNull(messageStartingWith("【参考资料】"));
        assertTrue(savedAnswer().getSources().isEmpty());
        verify(lock).unlock();
    }

    @Test
    void emptyEvidenceIsPassedToGenerationRatherThanAbortingChat() {
        prepare(List.of("信用分恢复规则"), List.of(
                result(0, "信用分恢复规则", IntentEnum.KNOWLEDGE, List.of())));

        service.chatStream(request("信用分恢复规则"), emitter, 9L);

        assertEquals(1, finalModelCalls.get());
        assertNoProcessingHints();
        verifyNoInteractions(rerank);
        assertNull(messageStartingWith("【参考资料】"));
        assertEquals("success", savedAnswer().getStatus());
        verify(lock).unlock();
    }

    @Test
    void searchFailureDoesNotLeakIntoGenerationAndHealthyEvidenceIsKept() {
        prepare(List.of("失败问题", "正常问题"), List.of(
                result(0, "失败问题", IntentEnum.KNOWLEDGE, List.of())
                        .setErrorMessage("test-private-search-error"),
                result(1, "正常问题", IntentEnum.KNOWLEDGE, List.of(reference(12)))));

        service.chatStream(request("失败问题和正常问题"), emitter, 9L);

        assertEquals(1, finalModelCalls.get());
        assertNoProcessingHints();
        assertFalse(modelRequest.get().toString().contains("test-private-search-error"));
        assertTrue(messageStartingWith("【参考资料】").contains("测试资料12"));
        assertEquals(List.of(12L), savedAnswer().getSources().stream().map(NeedChunksResp::getChunkId).toList());
        verify(lock).unlock();
    }

    @Test
    void globalRerankReceivesAllDeduplicatedCandidatesAndOnlySelectedEvidenceIsExposed() {
        List<String> questions = List.of("问题A", "问题B", "问题C", "问题D");
        prepare(questions, List.of(
                result(0, "问题A", IntentEnum.KNOWLEDGE,
                        List.of(reference(11), reference(12), reference(13))),
                result(1, "问题B", IntentEnum.KNOWLEDGE,
                        List.of(reference(21), reference(22), reference(23))),
                result(2, "问题C", IntentEnum.NO_MATCH,
                        List.of(reference(31), reference(32), reference(33))),
                result(3, "问题D", IntentEnum.KNOWLEDGE,
                        List.of(reference(41), reference(42), reference(43)))));
        when(rewrite.rewrite(anyString(), anyList())).thenReturn(new RewriteResultDTO()
                .setRewrittenQuestion("完整的改写问题").setSubQuestions(questions));
        doReturn(List.of(reference(11), reference(12), reference(21), reference(22), reference(31)))
                .when(rerank).rerank(eq("完整的改写问题"), anyList());

        service.chatStream(request("原始复合问题"), emitter, 9L);

        var pipelineOrder = inOrder(processor, rerank);
        pipelineOrder.verify(processor).process(questions);
        pipelineOrder.verify(rerank).rerank(eq("完整的改写问题"), argThat(candidates ->
                candidates.size() == 12 && candidates.stream().map(Reference::getChunkId)
                        .collect(Collectors.toSet()).equals(Set.of(
                                11L, 12L, 13L, 21L, 22L, 23L, 31L, 32L, 33L, 41L, 42L, 43L))));
        pipelineOrder.verifyNoMoreInteractions();
        assertEquals(1, finalModelCalls.get());
        assertOriginalQuestionLast("原始复合问题");
        assertNoProcessingHints();
        String context = messageStartingWith("【参考资料】");
        assertFalse(context.contains("测试资料41"));
        assertFalse(context.contains("测试资料13"));
        StoreDialogueDTO saved = savedAnswer();
        assertEquals(List.of(11L, 12L, 21L, 22L, 31L),
                saved.getSources().stream().map(NeedChunksResp::getChunkId).toList());
        verify(lock).unlock();
    }

    @Test
    void generationKeepsOriginalQuestionAndHistoryWhileRetrievalUsesRewrittenQuestion() {
        String original = "那我还能签到吗？";
        String rewritten = "信用分低于60分时，还能签到吗？";
        prepare(List.of(rewritten), List.of(
                result(0, rewritten, IntentEnum.KNOWLEDGE, List.of(reference(11)))));
        SearchSummaryMemoryDTO history = new SearchSummaryMemoryDTO()
                .setSummary("用户在讨论会议室预约规则")
                .setMemory(List.of(
                        new SearchMemoryDTO().setRole("user").setContent("我的信用分低于60分。"),
                        new SearchMemoryDTO().setRole("assistant").setContent("你想了解哪些影响？")));
        when(summary.summaryMemory(1L, 9L)).thenReturn(history);

        service.chatStream(request(original), emitter, 9L);

        verify(rewrite).rewrite(original, history.getMemory());
        verify(processor).process(List.of(rewritten));
        verify(rerank).rerank(eq(rewritten), anyList());
        verify(messages).saveQuestion(1L, 9L, original);
        assertOriginalQuestionLast(original);
        assertNoProcessingHints();
        JsonArray modelMessages = modelRequest.get().getAsJsonArray("messages");
        assertTrue(modelMessages.get(1).getAsJsonObject().get("content").getAsString()
                .contains("用户在讨论会议室预约规则"));
        assertEquals("我的信用分低于60分。", modelMessages.get(2).getAsJsonObject()
                .get("content").getAsString());
        assertEquals("assistant", modelMessages.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals(1, finalModelCalls.get());
        assertEquals("success", savedAnswer().getStatus());
        verify(lock).unlock();
    }

    @Test
    void finalModelFailureSavesFailedAnswerAndReleasesConversationLock() {
        prepare(List.of("你好"), List.of(result(0, "你好", IntentEnum.CHAT, List.of())));
        modelStatus = 503;

        assertThrows(ServiceException.class, () -> service.chatStream(request("你好"), emitter, 9L));

        StoreDialogueDTO saved = savedAnswer();
        assertEquals("failed", saved.getStatus());
        assertEquals("模型连接失败", saved.getErrorMessage());
        assertEquals(1, finalModelCalls.get());
        verify(lock).unlock();
    }

    private void prepare(List<String> questions, List<SubQuestionResultDTO> results) {
        when(rewrite.rewrite(anyString(), anyList())).thenReturn(new RewriteResultDTO()
                .setRewrittenQuestion(String.join("；", questions)).setSubQuestions(questions));
        when(processor.process(questions)).thenReturn(results);
    }

    private SubQuestionResultDTO result(int index, String question, IntentEnum intent, List<Reference> refs) {
        return new SubQuestionResultDTO().setIndex(index).setQuestion(question)
                .setIntent(intent).setReferences(refs);
    }

    private Reference reference(long id) {
        return new Reference().setChunkId(id).setDocId(20L).setDocName("规则.txt").setContent("测试资料" + id);
    }

    private QuestionReq request(String question) {
        QuestionReq request = new QuestionReq();
        request.setConversationId(1L);
        request.setQuestion(question);
        return request;
    }

    private void assertNoProcessingHints() {
        assertNull(messageStartingWith("【子问题处理结果】"));
        assertNull(messageStartingWith("【本次处理提示】"));
        String body = modelRequest.get().toString();
        assertFalse(body.contains("referenceIds"));
        assertFalse(body.contains("errorMessage"));
        assertFalse(body.contains("CLARIFY"));
        assertFalse(body.contains("资料ID："));
    }

    private String messageStartingWith(String prefix) {
        for (var message : modelRequest.get().getAsJsonArray("messages")) {
            String content = message.getAsJsonObject().get("content").getAsString();
            if (content.startsWith(prefix)) {
                return content;
            }
        }
        return null;
    }

    private void assertOriginalQuestionLast(String original) {
        JsonArray modelMessages = modelRequest.get().getAsJsonArray("messages");
        assertEquals(original, modelMessages.get(modelMessages.size() - 1)
                .getAsJsonObject().get("content").getAsString());
    }

    private int occurrences(String text, String part) {
        return (text.length() - text.replace(part, "").length()) / part.length();
    }

    private StoreDialogueDTO savedAnswer() {
        ArgumentCaptor<StoreDialogueDTO> captured = ArgumentCaptor.forClass(StoreDialogueDTO.class);
        verify(messages, times(1)).storeDialogue(captured.capture());
        return captured.getValue();
    }
}
