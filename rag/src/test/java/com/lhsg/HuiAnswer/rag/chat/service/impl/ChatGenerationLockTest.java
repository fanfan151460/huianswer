package com.lhsg.HuiAnswer.rag.chat.service.impl;

import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.chat.build.task.ChatGenerationManager;
import com.lhsg.HuiAnswer.rag.chat.build.task.ChatGenerationTask;
import com.lhsg.HuiAnswer.rag.chat.dto.req.QuestionReq;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.service.ISummaryService;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.process.SubQuestionProcessor;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import com.lhsg.HuiAnswer.rag.retrieval.rewrite.RewriteQuestion;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证任务收尾、解锁和终态事件的顺序，不连接中间件或外部模型。 */
class ChatGenerationLockTest {
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ChatGenerationManager manager = mock(ChatGenerationManager.class);
    private final IMessageService messages = mock(IMessageService.class);
    private final ISummaryService summaries = mock(ISummaryService.class);
    private final RewriteQuestion rewrite = mock(RewriteQuestion.class);
    private final SubQuestionProcessor processor = mock(SubQuestionProcessor.class);
    private final ThreadPoolExecutor summaryExecutor = mock(ThreadPoolExecutor.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final QuestionReq req = new QuestionReq();
    private final ChatGenerationTask task = new ChatGenerationTask()
            .setGenerationId("test-generation").setConversationId(1L).setUserId(9L).setEmitter(emitter);
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        req.setConversationId(1L);
        req.setQuestion("测试问题");
        ApiUrl apiUrl = new ApiUrl();
        apiUrl.setChatUrl("http://test.invalid");
        apiUrl.setChatPath("/chat");
        // 拦截器直接返回内存 SSE，绝不建立网络连接。
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"测试回答\"},\"finish_reason\":\"stop\"}]}\n\n"
                                        + "data: [DONE]\n\n",
                                MediaType.get("text/event-stream"))).build()).build();
        service = new ChatServiceImpl(processor, mock(RerankChunks.class), rewrite, http,
                new Gson(), messages, redisson, mock(IConversationService.class), summaries,
                apiUrl, manager, summaryExecutor);
        ReflectionTestUtils.setField(service, "chatModel", "test-model");
        ReflectionTestUtils.setField(service, "apiKey", "test-only");
        ReflectionTestUtils.setField(service, "modelWait", 180L);
        ReflectionTestUtils.setField(service, "answerMaxTokens", 256);
        when(redisson.getLock("huiAnswer:lock:conversation:1")).thenReturn(lock);
        doAnswer(invocation -> {
            task.checkStopped();
            return null;
        }).when(manager).checkStopped(task);
        doAnswer(invocation -> {
            task.finish();
            return null;
        }).when(manager).finish(task);
        // 模拟 SSE 正常完成也会触发连接结束回调。
        doAnswer(invocation -> {
            task.cancel("流式连接已关闭");
            return null;
        }).when(emitter).complete();
    }

    @Test
    void successIsStoredAndCleanedBeforeUnlockAndDone() throws Exception {
        when(lock.tryLock()).thenReturn(true);
        when(messages.saveQuestion(1L, 9L, "测试问题")).thenReturn(1);
        when(summaries.summaryMemory(1L, 9L)).thenReturn(new SearchSummaryMemoryDTO());
        when(rewrite.rewrite("测试问题", List.of())).thenReturn(new RewriteResultDTO()
                .setRewrittenQuestion("测试问题").setSubQuestions(List.of("测试问题")));
        when(processor.process(List.of("测试问题"))).thenReturn(List.of());

        runGeneration();

        InOrder order = inOrder(lock, messages, manager, emitter, summaryExecutor);
        order.verify(lock).tryLock();
        order.verify(messages).storeDialogue(argThat(dto ->
                "success".equals(dto.getStatus()) && "测试回答".equals(dto.getContent())));
        order.verify(manager).finish(task);
        order.verify(lock).unlock();
        order.verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        order.verify(emitter).complete();
        order.verify(summaryExecutor).execute(any(Runnable.class));
        verify(manager, times(1)).finish(task);
        assertTrue(task.isFinished());
        assertFalse(task.getStopped().get());
    }

    @Test
    void reasoningOnlyLengthCompletionCannotBeReportedAsSuccess() {
        String stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"测试思考\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                + "data: [DONE]\n\n";
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service, "readStream",
                ResponseBody.create(stream, MediaType.get("text/event-stream")),
                new com.lhsg.HuiAnswer.rag.chat.dto.resp.LLMChatResp(), new StringBuilder(),
                task));
    }

    @Test
    void capturesThinkingUsageWithoutSendingItAsAnswer() {
        String stream = "data: {\"usage\":null,\"choices\":[{\"delta\":{\"reasoning_content\":\"内部思考\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"usage\":{\"total_tokens\":100,\"completion_tokens\":12,\"completion_tokens_details\":{\"reasoning_tokens\":8}},"
                + "\"choices\":[{\"delta\":{\"content\":\"答复\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        var response = new com.lhsg.HuiAnswer.rag.chat.dto.resp.LLMChatResp();
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "readStream",
                ResponseBody.create(stream, MediaType.get("text/event-stream")),
                response, new StringBuilder(), task));
        assertEquals("答复", response.getAnswer());
        assertEquals(100, response.getTokens());
        assertEquals(Integer.valueOf(12), response.getCompletionTokens());
        assertEquals(Integer.valueOf(8), response.getReasoningTokens());
    }

    @Test
    void lengthCompletionWithBodyKeepsPartialAnswer() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"部分回答\"},\"finish_reason\":\"length\"}]}\n\n"
                + "data: [DONE]\n\n";
        var response = new com.lhsg.HuiAnswer.rag.chat.dto.resp.LLMChatResp();
        StringBuilder answer = new StringBuilder();
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "readStream",
                ResponseBody.create(stream, MediaType.get("text/event-stream")),
                response, answer, task));
        assertEquals("部分回答", response.getAnswer());
        assertEquals("length", response.getFinishReason());
    }

    @Test
    void failureIsCleanedAndUnlockedBeforeErrorEvent() throws Exception {
        when(lock.tryLock()).thenReturn(true);
        when(messages.saveQuestion(1L, 9L, "测试问题"))
                .thenThrow(new ServiceException("模拟保存问题失败"));

        runGeneration();

        InOrder order = inOrder(messages, manager, lock, emitter);
        order.verify(messages).saveQuestion(1L, 9L, "测试问题");
        order.verify(manager).finish(task);
        order.verify(lock).unlock();
        order.verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        order.verify(emitter).complete();
        verifyNoInteractions(summaryExecutor);
        assertTrue(task.isFinished());
        assertFalse(task.getStopped().get(), "结束回调不能把已失败收尾的任务改成用户停止");
    }

    @Test
    void busyConversationIsRejectedWithoutUnlockingAnotherTask() throws Exception {
        when(lock.tryLock()).thenReturn(false);

        runGeneration();

        verify(lock, never()).unlock();
        verifyNoInteractions(messages, summaries, rewrite, processor, summaryExecutor);
        InOrder order = inOrder(manager, emitter);
        order.verify(manager).finish(task);
        order.verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        order.verify(emitter).complete();
        assertTrue(task.isFinished());
    }

    @Test
    void cleanupFailureStillReleasesTheAcquiredLock() {
        when(lock.tryLock()).thenReturn(true);
        when(messages.saveQuestion(1L, 9L, "测试问题"))
                .thenThrow(new ServiceException("模拟保存问题失败"));
        doAnswer(invocation -> {
            task.finish();
            throw new IllegalStateException("模拟清理异常");
        }).when(manager).finish(task);

        assertDoesNotThrow(this::runGeneration);

        verify(lock).unlock();
        verify(emitter).complete();
        verifyNoInteractions(summaryExecutor);
    }

    private void runGeneration() {
        ReflectionTestUtils.invokeMethod(service, "runGeneration", req, task);
    }
}