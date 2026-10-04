package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil.EmbeddingChunks;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeChunkServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeChunkServiceTest {
    @Mock private EmbeddingChunks embedding;
    @Mock private KnowledgeChunkMapper mapper;
    private KnowledgeChunkServiceImpl service;

    @BeforeEach
    void setup() {
        service = new KnowledgeChunkServiceImpl(embedding, mapper);
        ReflectionTestUtils.setField(service, "CHUNK_SIZE", 500);
        ReflectionTestUtils.setField(service, "OVERLAP", 100);
    }

    @Test
    void shouldKeepShortDocument() {
        assertEquals(List.of("年假申请需要提前三天提交。"), service.toChunks("年假申请需要提前三天提交。"));
    }

    @Test
    void shouldStopOnceDocumentEndIsReached() {
        assertEquals(1, service.toChunks("a".repeat(500)).size());
        assertEquals(2, service.toChunks("a".repeat(900)).size());
    }

    @Test
    void shouldKeepLastPartialChunkAndExpectedOverlap() {
        String text = "a".repeat(400) + "b".repeat(100) + "c".repeat(300) + "d".repeat(100) + "e".repeat(300);
        List<String> chunks = service.toChunks(text);
        assertEquals(List.of(text.substring(0, 500), text.substring(400, 900), text.substring(800, 1200)), chunks);
        assertEquals(chunks.get(0).substring(400), chunks.get(1).substring(0, 100));
    }

    @Test
    void shouldNotBreakEmojiAtBoundary() {
        String text = "a".repeat(499) + "😀" + "b".repeat(20);
        List<String> chunks = service.toChunks(text);
        assertTrue(chunks.get(0).endsWith("😀"));
        for (String chunk : chunks) {
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
        }
    }

    @Test
    void shouldPairEachChunkWithItsOwnIndexAndEmbedding() {
        List<float[]> vectors = List.of(vector(1), vector(2), vector(3));
        when(embedding.doEmbedding(anyList())).thenReturn(vectors);
        List<KnowledgeChunkDO> written = new ArrayList<>();
        when(mapper.insert(any(KnowledgeChunkDO.class))).thenAnswer(call -> {
            written.add(call.getArgument(0));
            return 1;
        });
        // 同时捕获旧版的集合插入，使断言直接检查配对结果。
        lenient().when(mapper.insert(anyCollection())).thenAnswer(call -> {
            written.addAll(call.getArgument(0));
            return List.of();
        });
        service.saveChunks(List.of("第一块", "第二块", "第三块"), 7L, 8L);
        assertEquals(3, written.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, written.get(i).getChunkIndex());
            assertEquals(List.of("第一块", "第二块", "第三块").get(i), written.get(i).getContent());
            assertArrayEquals(vectors.get(i), written.get(i).getEmbedding());
            assertEquals(7L, written.get(i).getKbId());
            assertEquals(8L, written.get(i).getDocId());
        }
    }

    private float[] vector(float value) {
        float[] vector = new float[4096];
        vector[0] = value;
        return vector;
    }
}