package com.lhsg.HuiAnswer.rag.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil.EmbeddingChunks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实数据库和 Tika；原文件和模型用内存替代，不调用收费 API 或删除磁盘文件。 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "HUIANSWER_DB_PASSWORD", matches = ".+")
@Transactional
class KnowledgeDocumentApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KnowledgeBaseMapper baseMapper;
    @Autowired private KnowledgeDocumentMapper documentMapper;
    @Autowired private KnowledgeChunkMapper chunkMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private LocalDocumentStorage storage;
    @MockitoBean private EmbeddingChunks embedding;
    @MockitoBean private com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer producer;
    @MockitoBean private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService service;
    private final Map<String, byte[]> files = new HashMap<>();

    @BeforeEach
    void mockFiles() throws Exception {
        files.clear();
        var operations = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(embedding.doEmbedding(anyList())).thenAnswer(call ->
                call.<java.util.List<String>>getArgument(0).stream().map(text -> new float[4096]).toList());
        when(storage.save(anyLong(), any(MultipartFile.class), anyString())).thenAnswer(call -> {
            MultipartFile file = call.getArgument(1);
            String path = "memory/" + UUID.randomUUID();
            files.put(path, file.getBytes());
            return path;
        });
        when(storage.open(anyString())).thenAnswer(call -> new ByteArrayInputStream(files.get(call.getArgument(0))));
    }

    @Test
    void shouldParseChineseMarkdownAndPersistMatchingChunks() throws Exception {
        long kbId = createBase("文档导入测试");
        String text = "# 员工制度\n\n年假需要提前三天申请。";
        JsonNode data = upload(kbId, "../员工制度.md", text.getBytes(StandardCharsets.UTF_8));
        assertEquals("员工制度.md", data.path("docName").asText());
        assertEquals(kbId, data.path("kbId").asLong());
        assertEquals("success", data.path("status").asText());
        assertEquals("success", data.path("parseStatus").asText());
        assertEquals(1, data.path("chunkCount").asInt());
        var rows = chunkMapper.selectList(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                .eq(KnowledgeChunkDO::getDocId, data.path("id").asLong()));
        assertEquals(1, rows.size());
        assertEquals(text, rows.get(0).getContent());
        assertFalse(data.has("filePath"));
        var document = documentMapper.selectById(data.path("id").asLong());
        assertEquals(kbId, document.getKbId());
        assertTrue(files.containsKey(document.getFilePath()));
        assertEquals(text.length(), objectMapper.readTree(document.getMetadata()).path("textLength").asInt());
    }

    @Test
    void shouldKeepFullParsedLengthAndSeparateSameNamedUploads() throws Exception {
        long kbId = createBase("长文本库");
        String text = "a".repeat(1999) + "😀" + "后续内容".repeat(50);
        JsonNode first = upload(kbId, "sample.txt", text.getBytes(StandardCharsets.UTF_8));
        JsonNode second = upload(kbId, "sample.txt", "另一份原文".getBytes(StandardCharsets.UTF_8));
        assertEquals(text.length(), first.path("textLength").asInt());
        var rows = chunkMapper.selectList(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                .eq(KnowledgeChunkDO::getDocId, first.path("id").asLong()));
        assertEquals(6, rows.size());
        assertTrue(rows.stream().anyMatch(chunk -> chunk.getContent().contains("😀")));
        assertEquals(6, first.path("chunkCount").asInt());
        var firstDoc = documentMapper.selectById(first.path("id").asLong());
        var secondDoc = documentMapper.selectById(second.path("id").asLong());
        assertNotEquals(firstDoc.getFilePath(), secondDoc.getFilePath());
    }

    @Test
    void shouldRetainFailedDocumentForInvalidPdfAndWhitespaceText() throws Exception {
        long kbId = createBase("解析失败测试");
        for (String name : new String[]{"invalid.pdf", "blank.txt"}) {
            byte[] content = (name.endsWith("pdf") ? "not a pdf" : " \n\t").getBytes(StandardCharsets.UTF_8);
            JsonNode data = upload(kbId, name, content);
            assertEquals("failed", data.path("status").asText());
            assertEquals("failed", data.path("parseStatus").asText());
            assertFalse(data.path("errorMessage").asText().isBlank());
            assertEquals("failed", documentMapper.selectById(data.path("id").asLong()).getStatus());
        }
    }

    @Test
    void shouldRejectBadUploadsBeforeWritingFileOrDocument() throws Exception {
        long kbId = createBase("参数测试");
        long before = documentMapper.selectCount(null);
        for (MockMultipartFile file : new MockMultipartFile[]{
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]),
                new MockMultipartFile("file", "script.exe", "application/octet-stream", new byte[]{1}),
                new MockMultipartFile("file", "huge.txt", "text/plain", new byte[10 * 1024 * 1024 + 1])}) {
            mvc.perform(multipart(url(kbId)).file(file)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("A000001"));
        }
        mvc.perform(multipart(url(kbId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"));
        for (long badId : new long[]{0, Long.MAX_VALUE}) {
            mvc.perform(multipart(url(badId)).file(new MockMultipartFile("file", "a.txt",
                            "text/plain", "有效文本".getBytes(StandardCharsets.UTF_8))))
                    .andExpect(jsonPath("$.code").value("A000001"));
        }
        verify(storage, never()).save(anyLong(), any(), anyString());
        assertEquals(before, documentMapper.selectCount(null));
    }

    @Test
    void shouldScopeDocumentListAndHideDeletedRecords() throws Exception {
        long first = createBase("库一");
        long second = createBase("库二");
        long firstDoc = upload(first, "first.txt", "库一原文".getBytes(StandardCharsets.UTF_8)).path("id").asLong();
        upload(second, "second.txt", "库二原文".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(url(first))).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(firstDoc));
        documentMapper.deleteById(firstDoc);
        mvc.perform(get(url(first))).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(delete("/api/knowledge-base/" + second)).andExpect(jsonPath("$.code").value("0"));
        mvc.perform(get(url(second))).andExpect(jsonPath("$.code").value("A000001"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackChunkBatchesAndKeepFailedDocument() throws Exception {
        long kbId = createBase("导入回滚测试专用库");
        try {
            // 共 101 块；最后一块给错维度，验证第二批失败会回滚已 flush 的第一批。
            when(embedding.doEmbedding(anyList())).thenAnswer(call -> {
                int count = call.<java.util.List<String>>getArgument(0).size();
                return IntStream.range(0, count)
                        .mapToObj(i -> new float[i == count - 1 ? 1 : 4096]).toList();
            });
            JsonNode data = upload(kbId, "rollback.txt", "a".repeat(40500).getBytes(StandardCharsets.UTF_8));
            assertEquals("failed", data.path("status").asText());
            assertEquals("parsed", data.path("parseStatus").asText());
            assertEquals(0, data.path("chunkCount").asInt());
            assertFalse(data.path("errorMessage").asText().isBlank());
            long docId = data.path("id").asLong();
            assertEquals("failed", documentMapper.selectById(docId).getStatus());
            assertEquals(0L, chunkMapper.selectCount(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                    .eq(KnowledgeChunkDO::getDocId, docId)));
        } finally {
            // 只清理本测试自己提交的记录；文件存储是内存，不操作磁盘文件。
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("DELETE FROM t_knowledge_chunk WHERE kb_id = ?", kbId);
                jdbc.update("DELETE FROM t_knowledge_document WHERE kb_id = ?", kbId);
                jdbc.update("DELETE FROM t_knowledge_base WHERE id = ?", kbId);
            });
        }
    }

    private long createBase(String name) {
        var base = new KnowledgeBaseDO().setName(name);
        baseMapper.insert(base);
        return base.getId();
    }

    private JsonNode upload(long kbId, String name, byte[] bytes) throws Exception {
        MvcResult result = mvc.perform(multipart(url(kbId))
                        .file(new MockMultipartFile("file", name, "application/octet-stream", bytes)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0")).andReturn();
        JsonNode accepted = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
        assertEquals("pending", accepted.path("status").asText());
        long docId = accepted.path("id").asLong();
        var msg = new com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg().setKbId(kbId).setDocId(docId);
        try { service.consumeDocument(msg); }
        catch (com.lhsg.HuiAnswer.framework.exception.ServiceException expectedRetry) { }
        MvcResult current = mvc.perform(get(url(kbId) + "/" + docId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0")).andReturn();
        return objectMapper.readTree(current.getResponse().getContentAsByteArray()).path("data");
    }

    private String url(long kbId) {
        return "/api/knowledge-base/" + kbId + "/documents";
    }
}
