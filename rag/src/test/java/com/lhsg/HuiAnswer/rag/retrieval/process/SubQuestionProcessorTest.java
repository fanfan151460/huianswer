package com.lhsg.HuiAnswer.rag.retrieval.process;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.config.EmitterPoolConfig;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.KnowledgeCatalogDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.SubQuestionResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.Identify;
import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChunks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubQuestionProcessorTest {
    private final Identify identify = mock(Identify.class);
    private final SearchChunks search = mock(SearchChunks.class);
    private final List<KnowledgeCatalogDTO> catalog =
            List.of(new KnowledgeCatalogDTO().setKbId(2L).setName("会议室"));
    private ThreadPoolExecutor executor;
    private SubQuestionProcessor processor;

    @BeforeEach
    void setUp() {
        executor = new EmitterPoolConfig().subQuestionExecutor();
        processor = new SubQuestionProcessor(identify, search);
        ReflectionTestUtils.setField(processor, "subQuestionExecutor", executor);
        when(identify.loadCatalog()).thenReturn(catalog);
    }

    @AfterEach
    void shutDown() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void routingAndRetrievalOverlapAndCollectionWaitsForAllInInputOrder() throws Exception {
        CountDownLatch searchesStarted = new CountDownLatch(2);
        CountDownLatch clarifyStarted = new CountDownLatch(1);
        CountDownLatch fastSearched = new CountDownLatch(1);
        CountDownLatch releaseSlowSearch = new CountDownLatch(1);
        CountDownLatch releaseClarify = new CountDownLatch(1);
        when(identify.identifyOne(anyString(), anyInt(), eq(catalog))).thenAnswer(call -> {
            String question = call.getArgument(0);
            int index = call.getArgument(1);
            if ("澄清".equals(question)) {
                clarifyStarted.countDown();
                assertTrue(releaseClarify.await(5, TimeUnit.SECONDS));
                return route(index, IntentEnum.CLARIFY, List.of());
            }
            return route(index, IntentEnum.KNOWLEDGE, List.of(2L));
        });
        when(search.mixSearch(eq(List.of(2L)), anyString())).thenAnswer(call -> {
            String question = call.getArgument(1);
            searchesStarted.countDown();
            assertTrue(searchesStarted.await(5, TimeUnit.SECONDS));
            if ("慢问题".equals(question)) {
                assertTrue(releaseSlowSearch.await(5, TimeUnit.SECONDS));
                return List.of(reference(11));
            }
            fastSearched.countDown();
            return List.of(reference(12));
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<SubQuestionResultDTO>> result = caller.submit(
                    () -> processor.process(List.of("慢问题", "快问题", "澄清")));
            assertTrue(searchesStarted.await(5, TimeUnit.SECONDS), "检索任务应同时进入");
            assertTrue(clarifyStarted.await(5, TimeUnit.SECONDS));
            // 其他子问题仍在路由时，快问题已经完成召回，没有全体路由的等待屏障。
            assertTrue(fastSearched.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "慢任务未结束，不能提前交给最终生成");
            releaseSlowSearch.countDown();
            releaseClarify.countDown();
            List<SubQuestionResultDTO> results = result.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("慢问题", "快问题", "澄清"),
                    results.stream().map(SubQuestionResultDTO::getQuestion).toList());
            assertEquals(List.of(0, 1, 2), results.stream().map(SubQuestionResultDTO::getIndex).toList());
            assertEquals(11, results.get(0).getReferences().get(0).getChunkId());
            assertEquals(12, results.get(1).getReferences().get(0).getChunkId());
            assertTrue(results.get(2).getReferences().isEmpty());
            verify(identify, times(1)).loadCatalog();
            verify(search, never()).mixSearch(any(), eq("澄清"));
        } finally {
            releaseSlowSearch.countDown();
            releaseClarify.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void searchScopeAndNoSearchBranchesRemainSeparate() {
        when(identify.identifyOne(eq("选库"), eq(0), eq(catalog)))
                .thenReturn(route(0, IntentEnum.KNOWLEDGE, List.of(2L)));
        when(identify.identifyOne(eq("全库"), eq(1), eq(catalog)))
                .thenReturn(route(1, IntentEnum.NO_MATCH, List.of()));
        when(identify.identifyOne(eq("聊天"), eq(2), eq(catalog)))
                .thenReturn(route(2, IntentEnum.CHAT, List.of()));
        when(identify.identifyOne(eq("澄清"), eq(3), eq(catalog)))
                .thenReturn(route(3, IntentEnum.CLARIFY, List.of()));
        when(search.mixSearch(List.of(2L), "选库")).thenReturn(List.of(reference(11)));
        when(search.mixSearch(null, "全库")).thenReturn(List.of());

        List<SubQuestionResultDTO> results = processor.process(List.of("选库", "全库", "聊天", "澄清"));

        verify(search).mixSearch(List.of(2L), "选库");
        verify(search).mixSearch(null, "全库");
        verifyNoMoreInteractions(search);
        assertNull(results.get(1).getErrorMessage(), "无资料不同于检索失败");
        assertTrue(results.subList(1, 4).stream().allMatch(item -> item.getReferences().isEmpty()));
    }

    @Test
    void oneSearchFailureDoesNotDiscardOtherResults() {
        when(identify.identifyOne(anyString(), anyInt(), eq(catalog))).thenAnswer(call ->
                route(call.getArgument(1), IntentEnum.KNOWLEDGE, List.of(2L)));
        when(search.mixSearch(List.of(2L), "失败问题"))
                .thenThrow(new IllegalStateException("private backend error"));
        when(search.mixSearch(List.of(2L), "正常问题")).thenReturn(List.of(reference(12)));

        List<SubQuestionResultDTO> results = processor.process(List.of("失败问题", "正常问题"));

        assertEquals("该子问题的资料检索暂时失败，请稍后再试", results.get(0).getErrorMessage());
        assertTrue(results.get(0).getReferences().isEmpty());
        assertNull(results.get(1).getErrorMessage());
        assertEquals(12, results.get(1).getReferences().get(0).getChunkId());
    }

    @Test
    void catalogAndPoolFailuresPropagateInsteadOfBecomingNoMatch() {
        when(identify.loadCatalog()).thenThrow(new IllegalStateException("test catalog unavailable"));
        assertThrows(IllegalStateException.class, () -> processor.process(List.of("问题")));
        verify(identify, never()).identifyOne(anyString(), anyInt(), anyList());

        doReturn(catalog).when(identify).loadCatalog();
        executor.shutdown();
        ServiceException error = assertThrows(ServiceException.class,
                () -> processor.process(List.of("问题")));
        assertEquals("子问题处理繁忙，请稍后再试", error.getErrorMessage());
        verifyNoInteractions(search);
    }

    @Test
    void interruptedCollectorCancelsWorkersAndRestoresInterruptFlag() throws Exception {
        CountDownLatch searchStarted = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch collectorFinished = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> collectorFailure = new AtomicReference<>();
        when(identify.identifyOne(eq("问题"), eq(0), eq(catalog)))
                .thenReturn(route(0, IntentEnum.KNOWLEDGE, List.of(2L)));
        when(search.mixSearch(List.of(2L), "问题")).thenAnswer(call -> {
            searchStarted.countDown();
            try {
                releaseSearch.await();
            } catch (InterruptedException e) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new CancellationException("test worker cancelled");
            }
            return List.of(reference(11));
        });
        Thread caller = new Thread(() -> {
            try {
                processor.process(List.of("问题"));
            } catch (Throwable error) {
                collectorFailure.set(error);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                collectorFinished.countDown();
            }
        });
        caller.start();
        try {
            assertTrue(searchStarted.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(collectorFinished.await(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, collectorFailure.get());
            assertTrue(interruptRestored.get());
            assertTrue(workerInterrupted.await(5, TimeUnit.SECONDS));
        } finally {
            releaseSearch.countDown();
            caller.interrupt();
            caller.join(5000);
        }
    }

    @Test
    void emptyInputSkipsCatalogAndTasks() {
        assertTrue(processor.process(List.of()).isEmpty());
        assertTrue(processor.process(null).isEmpty());
        verifyNoInteractions(identify, search);
    }

    private IntentResultDTO route(int index, IntentEnum intent, List<Long> ids) {
        return new IntentResultDTO().setIndex(index).setIntent(intent).setKbIds(ids);
    }

    private Reference reference(long id) {
        return new Reference().setChunkId(id).setDocId(20L).setDocName("规则.txt").setContent("测试资料");
    }
}
