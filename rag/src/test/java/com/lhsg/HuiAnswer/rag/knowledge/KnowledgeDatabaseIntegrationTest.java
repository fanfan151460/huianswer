package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.*;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 PostgreSQL 检查向量映射和归属约束；每个用例的业务记录均回滚。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "HUIANSWER_DB_PASSWORD", matches = ".+")
@Transactional
class KnowledgeDatabaseIntegrationTest {
    @Autowired
    private KnowledgeBaseMapper baseMapper;
    @Autowired
    private KnowledgeDocumentMapper documentMapper;
    @Autowired
    private KnowledgeChunkMapper chunkMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRoundTripVectorJsonAndAuditFields() {
        KnowledgeBaseDO base = createBase("测试知识库");
        KnowledgeDocumentDO doc = createDocument(base.getId());
        float[] vector = unitVector(0);
        KnowledgeChunkDO chunk = createChunk(doc, vector);

        KnowledgeChunkDO stored = chunkMapper.selectById(chunk.getId());
        assertArrayEquals(vector, stored.getEmbedding());
        assertEquals(4096, stored.getEmbedding().length);
        assertNotNull(stored.getCreateTime());
        assertNotNull(stored.getUpdateTime());
        assertEquals(0, stored.getDeleted());
        assertEquals("success", documentMapper.selectById(doc.getId()).getStatus());
        assertTrue(documentMapper.selectById(doc.getId()).getMetadata().contains("txt"));
    }

    @Test
    void shouldRestrictRetrievalAndPhysicallyCleanChunksWhenDocumentIsDeleted() {
        KnowledgeDocumentDO first = createDocument(createBase("制度库").getId());
        KnowledgeDocumentDO other = createDocument(createBase("产品库").getId());
        KnowledgeChunkDO expected = createChunk(first, unitVector(0));
        createChunk(other, unitVector(0));

        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT c.id FROM t_knowledge_chunk c
                JOIN t_knowledge_document d ON d.id = c.doc_id AND d.kb_id = c.kb_id
                JOIN t_knowledge_base b ON b.id = c.kb_id
                WHERE c.kb_id = ? AND c.deleted = 0 AND d.deleted = 0
                  AND d.status = 'success' AND b.deleted = 0
                ORDER BY c.embedding <=> CAST(? AS vector) LIMIT 5
                """, Long.class, first.getKbId(), vectorText(unitVector(0)));
        assertEquals(List.of(expected.getId()), ids);

        assertEquals(1, documentMapper.deleteById(first.getId()));
        assertNull(documentMapper.selectById(first.getId()));
        assertEquals(1, chunkMapper.deletePhysicallyByDocId(first.getId()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_knowledge_chunk WHERE doc_id = ?", Long.class, first.getId()));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_knowledge_chunk WHERE doc_id = ?", Long.class, other.getId()));
    }

    @Test
    void shouldRejectWrongEmbeddingDimension() {
        KnowledgeDocumentDO doc = createDocument(createBase("维度检查").getId());
        assertThrows(DataIntegrityViolationException.class, () -> createChunk(doc, new float[] {1, 2, 3}));
    }

    @Test
    void shouldRejectChunkAssignedToAnotherKnowledgeBase() {
        KnowledgeDocumentDO doc = createDocument(createBase("文档所属库").getId());
        Long anotherKbId = createBase("另一个库").getId();
        KnowledgeChunkDO chunk = new KnowledgeChunkDO().setKbId(anotherKbId).setDocId(doc.getId())
                .setChunkIndex(0).setContent("归属错误测试").setEmbedding(unitVector(0));
        assertThrows(DataIntegrityViolationException.class, () -> chunkMapper.insert(chunk));
    }

    private KnowledgeBaseDO createBase(String name) {
        KnowledgeBaseDO base = new KnowledgeBaseDO().setName(name);
        baseMapper.insert(base);
        assertNotNull(base.getId());
        return base;
    }

    private KnowledgeDocumentDO createDocument(Long kbId) {
        KnowledgeDocumentDO doc = new KnowledgeDocumentDO().setKbId(kbId).setDocName("制度.txt")
                .setFilePath("integration-test/not-an-actual-file.txt")
                .setMetadata("{\"type\":\"txt\"}").setStatus("success");
        documentMapper.insert(doc);
        return doc;
    }

    private KnowledgeChunkDO createChunk(KnowledgeDocumentDO doc, float[] vector) {
        KnowledgeChunkDO chunk = new KnowledgeChunkDO().setKbId(doc.getKbId()).setDocId(doc.getId())
                .setChunkIndex(0).setContent("住宿报销上限为每日 300 元").setEmbedding(vector);
        chunkMapper.insert(chunk);
        return chunk;
    }

    private float[] unitVector(int index) {
        float[] vector = new float[4096];
        vector[index] = 1;
        return vector;
    }

    private String vectorText(float[] vector) {
        StringJoiner result = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            result.add(Float.toString(value));
        }
        return result.toString();
    }
}
