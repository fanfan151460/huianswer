package com.lhsg.HuiAnswer.rag.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.*;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.*;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "HUIANSWER_DB_PASSWORD", matches = ".+")
@Transactional
class KnowledgeBaseMutationIntegrationTest {
    private static final String BASE = "/api/knowledge-base";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private KnowledgeBaseMapper baseMapper;
    @Autowired
    private KnowledgeChunkMapper chunkMapper;
    @MockitoSpyBean
    private KnowledgeDocumentMapper documentMapper;
    @Autowired
    private IKnowledgeBaseService baseService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldRenameOnlyRequestedKnowledgeBase() throws Exception {
        Long id = createBase("旧名称");
        Long otherId = createBase("另一个库");
        String response = mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  新名称  "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.name").value("新名称")).andReturn()
                .getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        assertEquals(id.longValue(), data.path("id").asLong());
        assertTrue(data.hasNonNull("updateTime"));
        assertEquals("新名称", baseMapper.selectById(id).getName());
        assertEquals("另一个库", baseMapper.selectById(otherId).getName());
    }

    @Test
    void shouldDeleteAllOwnedChunksAndMarkDocumentsWithoutTouchingAnotherKnowledgeBase() throws Exception {
        Long id = createBase("待删除知识库");
        Long otherId = createBase("保留知识库");
        KnowledgeDocumentDO active = createDocument(id);
        KnowledgeDocumentDO alreadyDeleted = createDocument(id);
        KnowledgeDocumentDO other = createDocument(otherId);
        createChunk(active);
        KnowledgeChunkDO hiddenChunk = createChunk(alreadyDeleted);
        createChunk(other);
        documentMapper.deleteById(alreadyDeleted.getId());
        chunkMapper.deleteById(hiddenChunk.getId());

        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        assertNull(baseMapper.selectById(id));
        assertEquals(0L, count("t_knowledge_chunk", id, ""));
        assertEquals(2L, count("t_knowledge_document", id, " AND deleted = 1"));
        LocalDateTime deletedAt = jdbcTemplate.queryForObject(
                "SELECT update_time FROM t_knowledge_document WHERE id = ?", Timestamp.class,
                active.getId()).toLocalDateTime();
        assertFalse(deletedAt.isBefore(active.getUpdateTime()));
        assertNotNull(baseMapper.selectById(otherId));
        assertEquals(1L, count("t_knowledge_chunk", otherId, ""));
        assertEquals(1L, count("t_knowledge_document", otherId, " AND deleted = 0"));
    }

    @Test
    void shouldValidateRenameAndHandleEmptyMissingDeletedKnowledgeBases() throws Exception {
        Long id = createBase("参数验证库");
        for (String name : List.of("", " ", "a".repeat(129))) {
            mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", name))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        }
        assertEquals("参数验证库", baseMapper.selectById(id).getName());
        for (String invalidId : List.of("0", "-1", "not-a-number", Long.toString(Long.MAX_VALUE))) {
            mvc.perform(delete(BASE + "/" + invalidId)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("A000001"));
            mvc.perform(put(BASE + "/" + invalidId).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"改名\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        }
        // 用户已有的删除入口继续可用；空知识库也可删除。
        mvc.perform(delete(BASE + "/delete/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"));
        mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不能恢复\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        assertNull(baseMapper.selectById(id));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackItsOwnTransactionWhenDocumentDeletionFails() {
        // 关闭测试自身的事务，才能证明是 Service 的 @Transactional 在回滚。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long id = tx.execute(status -> {
            Long kbId = createBase("事务回滚专用测试库");
            createChunk(createDocument(kbId));
            return kbId;
        });
        assertNotNull(id);
        try {
            doThrow(new IllegalStateException("测试故障：片段删除后文档操作失败"))
                    .when(documentMapper).markDeletedByKbId(eq(id), any(LocalDateTime.class));
            assertThrows(IllegalStateException.class, () -> baseService.deleteBase(id));
            assertNotNull(baseMapper.selectById(id));
            assertEquals(1L, count("t_knowledge_document", id, " AND deleted = 0"));
            assertEquals(1L, count("t_knowledge_chunk", id, ""));
        } finally {
            reset(documentMapper);
            // 仅清理本用例提交的专用记录，不操作任何磁盘文件。
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM t_knowledge_chunk WHERE kb_id = ?", id);
                jdbcTemplate.update("DELETE FROM t_knowledge_document WHERE kb_id = ?", id);
                jdbcTemplate.update("DELETE FROM t_knowledge_base WHERE id = ?", id);
            });
        }
    }

    private Long createBase(String name) {
        KnowledgeBaseDO base = new KnowledgeBaseDO().setName(name);
        baseMapper.insert(base);
        return base.getId();
    }

    private KnowledgeDocumentDO createDocument(Long kbId) {
        KnowledgeDocumentDO doc = new KnowledgeDocumentDO().setKbId(kbId).setDocName("测试.txt")
                .setFilePath("integration-test/no-actual-file.txt").setStatus("success");
        documentMapper.insert(doc);
        return doc;
    }

    private KnowledgeChunkDO createChunk(KnowledgeDocumentDO doc) {
        float[] vector = new float[4096];
        vector[0] = 1;
        KnowledgeChunkDO chunk = new KnowledgeChunkDO().setKbId(doc.getKbId()).setDocId(doc.getId())
                .setChunkIndex(0).setContent("测试片段").setEmbedding(vector);
        chunkMapper.insert(chunk);
        return chunk;
    }

    private Long count(String table, Long kbId, String condition) {
        // 表名和附加条件仅来自本类固定常量，用户参数通过占位符绑定。
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE kb_id = ?" + condition,
                Long.class, kbId);
    }
}
