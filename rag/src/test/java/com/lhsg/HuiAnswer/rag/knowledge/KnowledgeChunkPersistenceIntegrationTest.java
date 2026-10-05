package com.lhsg.HuiAnswer.rag.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil.EmbeddingChunks;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** 真实 PostgreSQL 片段配对与独立事务回滚；测试向量由内存提供，不调用模型。 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "HUIANSWER_DB_PASSWORD", matches = ".+")
@Transactional
class KnowledgeChunkPersistenceIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer producer;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private IKnowledgeChunkService service;
    @Autowired private KnowledgeBaseMapper baseMapper;
    @Autowired private KnowledgeDocumentMapper documentMapper;
    @Autowired private KnowledgeChunkMapper chunkMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmbeddingChunks embedding;

    @Test
    void shouldPersistDistinctIndexesAndMatchingVectorsInCurrentTransaction() {
        KnowledgeDocumentDO document = createDocument();
        List<float[]> vectors = List.of(vector(1), vector(2), vector(3));
        when(embedding.doEmbedding(anyList())).thenReturn(vectors);
        service.saveChunks(service.prepareChunks(List.of("片段甲", "片段乙", "片段丙"), document.getKbId(), document.getId()));
        var rows = chunkMapper.selectList(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                .eq(KnowledgeChunkDO::getDocId, document.getId()).orderByAsc(KnowledgeChunkDO::getChunkIndex));
        assertEquals(3, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).getChunkIndex());
            assertEquals(List.of("片段甲", "片段乙", "片段丙").get(i), rows.get(i).getContent());
            assertEquals(document.getKbId(), rows.get(i).getKbId());
            assertArrayEquals(vectors.get(i), rows.get(i).getEmbedding());
            assertNotNull(rows.get(i).getId());
            assertNotNull(rows.get(i).getCreateTime());
            assertNotNull(rows.get(i).getUpdateTime());
            assertEquals(0, rows.get(i).getDeleted());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackFlushedFirstBatchWhenNextBatchConflicts() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        KnowledgeDocumentDO document = tx.execute(status -> {
            KnowledgeDocumentDO doc = createDocument();
            chunkMapper.insert(new KnowledgeChunkDO().setKbId(doc.getKbId()).setDocId(doc.getId())
                    .setChunkIndex(100).setContent("原有片段").setEmbedding(vector(9)));
            return doc;
        });
        assertNotNull(document);
        try {
            List<String> chunks = IntStream.range(0, 101).mapToObj(i -> "新片段" + i).toList();
            when(embedding.doEmbedding(anyList())).thenReturn(
                    IntStream.range(0, 101).mapToObj(i -> vector(i + 1)).toList());
            // 第一批 100 条已 flush，第二批与原有序号 100 冲突，仍须全部回滚。
            assertThrows(RuntimeException.class, () -> service.saveChunks(service.prepareChunks(chunks,
                    document.getKbId(), document.getId())));
            var rows = chunkMapper.selectList(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                    .eq(KnowledgeChunkDO::getDocId, document.getId()));
            assertEquals(1, rows.size());
            assertEquals("原有片段", rows.get(0).getContent());
            assertEquals(100, rows.get(0).getChunkIndex());
        } finally {
            // 仅清理测试自己提交的数据库记录，不操作磁盘文件。
            tx.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM t_knowledge_chunk WHERE kb_id = ?", document.getKbId());
                jdbc.update("DELETE FROM t_knowledge_document WHERE kb_id = ?", document.getKbId());
                jdbc.update("DELETE FROM t_knowledge_base WHERE id = ?", document.getKbId());
            });
        }
    }

    private KnowledgeDocumentDO createDocument() {
        var base = new KnowledgeBaseDO().setName("片段入库测试专用库");
        baseMapper.insert(base);
        var document = new KnowledgeDocumentDO().setKbId(base.getId()).setDocName("测试原文.txt")
                .setFilePath("test/no-actual-file.txt").setStatus("pending");
        documentMapper.insert(document);
        return document;
    }

    private float[] vector(float first) {
        float[] vector = new float[4096];
        vector[0] = first;
        return vector;
    }
}