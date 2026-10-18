package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.knowledge.ChunkStrategy.RecursionChunk;
import com.lhsg.HuiAnswer.rag.knowledge.enums.SplitStrategy;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeChunkServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecursionChunkTest {
    @Test
    void shortTextIsKeptIntact() {
        assertEquals(List.of("退货运费由商家承担。"),
                RecursionChunk.recursionChunk("退货运费由商家承担。", 200));
    }

    @Test
    void smallParagraphsAreMergedInOrder() {
        String text = "甲甲甲甲\n\n乙乙乙乙\n\n丙丙丙丙";
        assertEquals(List.of("甲甲甲甲\n\n乙乙乙乙\n\n", "丙丙丙丙"),
                RecursionChunk.recursionChunk(text, 12));
    }

    @Test
    void paragraphBoundariesTakePriorityOverSentences() {
        String text = "甲甲。乙乙。\n\n丙丙。丁丁。";
        assertEquals(List.of("甲甲。乙乙。\n\n", "丙丙。丁丁。"),
                RecursionChunk.recursionChunk(text, 8));
    }

    @Test
    void longParagraphFallsBackToSentencesAndKeepsPunctuation() {
        String text = "甲甲甲甲。乙乙乙乙。丙丙丙丙。";
        assertEquals(List.of("甲甲甲甲。乙乙乙乙。", "丙丙丙丙。"),
                RecursionChunk.recursionChunk(text, 10));
    }

    @Test
    void longSentenceFallsBackToCommas() {
        String text = "甲甲甲甲甲，乙乙乙乙乙，丙丙丙丙丙";
        assertEquals(List.of("甲甲甲甲甲，", "乙乙乙乙乙，", "丙丙丙丙丙"),
                RecursionChunk.recursionChunk(text, 8));
    }

    @Test
    void textWithoutSeparatorsFallsBackWithoutBreakingEmoji() {
        String text = "甲😀乙🙂丙🚀丁🌟戊".repeat(3);
        List<String> chunks = RecursionChunk.recursionChunk(text, 5);
        assertEquals(text, String.join("", chunks));
        assertEquals(6, chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.codePointCount(0, chunk.length()) <= 5);
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
        }
    }

    @Test
    void trailingSeparatorsAndFinalRemainderAreKept() {
        String text = "甲甲。\n\n乙乙。尾巴\n";
        List<String> chunks = RecursionChunk.recursionChunk(text, 4);
        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.codePointCount(0, chunk.length()) <= 4));
    }

    @Test
    void whitespaceOnlyChunksAreNotEmbedded() {
        List<String> chunks = RecursionChunk.recursionChunk("甲" + "\n".repeat(25) + "乙", 4);
        assertTrue(chunks.stream().noneMatch(String::isBlank));
        assertTrue(chunks.get(0).startsWith("甲"));
        assertTrue(chunks.get(chunks.size() - 1).endsWith("乙"));
    }

    @Test
    void invalidSizeAndEmptyTextAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecursionChunk.recursionChunk("内容", 0));
        assertThrows(IllegalArgumentException.class, () -> RecursionChunk.recursionChunk("内容", -1));
        assertThrows(ClientException.class, () -> RecursionChunk.recursionChunk(null, 10));
        assertThrows(ClientException.class, () -> RecursionChunk.recursionChunk(" \n ", 10));
    }

    @Test
    void messagesDefaultToOverlapAndServiceCanSelectRecursive() {
        assertEquals(SplitStrategy.OVERLAP, new DocumentMsg().getStrategy());
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(null, null);
        ReflectionTestUtils.setField(service, "chunkSize", 8);
        ReflectionTestUtils.setField(service, "overlap", 2);
        ReflectionTestUtils.setField(service, "recursionChunkSize", 8);
        assertEquals(List.of("甲甲甲甲。", "乙乙乙乙。"),
                service.toChunks("甲甲甲甲。乙乙乙乙。", SplitStrategy.RECURSIVE));
        assertEquals(service.toChunks("甲甲甲甲。乙乙乙乙。", SplitStrategy.OVERLAP),
                service.toChunks("甲甲甲甲。乙乙乙乙。", null));
    }
}
