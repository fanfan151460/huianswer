package com.lhsg.HuiAnswer.rag.knowledge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.handler.JsonbTypeHandler;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无数据库、磁盘写入或模型调用，验证导入状态及真实 TransactionTemplate 的回滚边界。 */
class KnowledgeDocumentServiceTest {
    private static final long KB_ID = 7L;
    private static final long DOC_ID = 11L;
    private static final String TEXT = "年假需要提前申请。";
    private KnowledgeBaseMapper baseMapper;
    private KnowledgeDocumentMapper documentMapper;
    private LocalDocumentStorage storage;
    private TikaDocumentParser parser;
    private IKnowledgeChunkService chunks;
    private RecordingTransactionManager transactions;
    private KnowledgeDocumentServiceImpl service;
    private MockMultipartFile file;
    private final List<Update> updates = new ArrayList<>();
    private boolean insertInTransaction;

    @BeforeEach
    void setUp() throws Exception {
        new MybatisConfiguration().addMapper(KnowledgeDocumentMapper.class);
        baseMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        storage = mock(LocalDocumentStorage.class);
        parser = mock(TikaDocumentParser.class);
        chunks = mock(IKnowledgeChunkService.class);
        transactions = new RecordingTransactionManager();
        service = new KnowledgeDocumentServiceImpl(baseMapper, documentMapper, storage, parser,
                new Gson(), chunks, new TransactionTemplate(transactions));
        var base = new KnowledgeBaseDO().setId(KB_ID).setName("测试知识库");
        when(baseMapper.selectById(KB_ID)).thenReturn(base);
        when(baseMapper.selectActiveForUpdate(KB_ID)).thenReturn(base);
        when(storage.save(eq(KB_ID), any(), eq("txt"))).thenReturn("memory/guide.txt");
        when(storage.open(anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(parser.parse(any(InputStream.class), eq("guide.txt"), eq("txt")))
                .thenReturn(new ParsedDocument(TEXT, "text/plain"));
        when(chunks.toChunks(TEXT)).thenReturn(List.of(TEXT));
        when(documentMapper.insert(any(KnowledgeDocumentDO.class))).thenAnswer(call -> {
            insertInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            call.<KnowledgeDocumentDO>getArgument(0).setId(DOC_ID);
            return 1;
        });
        when(documentMapper.updateById(any(KnowledgeDocumentDO.class))).thenAnswer(call -> {
            recordUpdate(call.getArgument(0));
            return 1;
        });
        file = new MockMultipartFile("file", "guide.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldCommitPendingBeforeParsingAndParseOutsideTransaction() throws Exception {
        when(parser.parse(any(), anyString(), anyString())).thenAnswer(call -> {
            assertTrue(insertInTransaction, "pending 登记必须在检查知识库的短事务里");
            assertEquals(1, transactions.commits);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new ParsedDocument(TEXT, "text/plain");
        });
        service.uploadDocument(KB_ID, file);
        assertEquals(2, transactions.commits);
    }

    @Test
    void shouldReturnSuccessWithSameMetadataAsSavedDocument() {
        var response = service.uploadDocument(KB_ID, file);
        assertEquals("success", response.getStatus());
        assertEquals("success", response.getParseStatus());
        assertEquals(TEXT.length(), response.getTextLength());
        assertEquals(1, response.getChunkCount());
        assertNull(response.getErrorMessage());
        assertEquals("success", updates.get(0).status());
        assertEquals(1, JsonParser.parseString(updates.get(0).metadata()).getAsJsonObject()
                .get("chunkCount").getAsInt());
    }

    @Test
    void shouldSaveFailedMetadataWhenParsingFails() {
        when(parser.parse(any(), anyString(), anyString())).thenThrow(new ClientException("无法提取文字"));
        var response = service.uploadDocument(KB_ID, file);
        assertEquals("failed", response.getStatus());
        assertEquals("failed", response.getParseStatus());
        assertEquals("无法提取文字", response.getErrorMessage());
        assertEquals(0, response.getChunkCount());
        assertEquals("failed", updates.get(0).status());
        verifyNoInteractions(chunks);
    }

    @Test
    void shouldRecordEmbeddingFailureInNewTransactionAfterRollback() {
        doThrow(new ServiceException("向量接口限流，请稍后重试"))
                .when(chunks).saveChunks(anyList(), eq(KB_ID), eq(DOC_ID));
        var response = service.uploadDocument(KB_ID, file);
        assertEquals("failed", response.getStatus());
        assertEquals("parsed", response.getParseStatus());
        assertEquals("向量接口限流，请稍后重试", response.getErrorMessage());
        assertEquals(0, response.getChunkCount());
        assertEquals(1, transactions.rollbacks);
        assertEquals(2, transactions.commits); // pending 登记 + failed 登记
        assertTrue(updates.get(0).inTransaction());
        assertEquals(1, updates.get(0).rollbacksBeforeUpdate());
    }

    @Test
    void shouldCatchSplittingFailureAndKeepFailedRecord() {
        when(chunks.toChunks(TEXT)).thenThrow(new ClientException("文档内容为空"));
        var response = service.uploadDocument(KB_ID, file);
        assertEquals("failed", response.getStatus());
        assertEquals("文档内容为空", response.getErrorMessage());
        assertEquals("failed", updates.get(0).status());
        verify(chunks, never()).saveChunks(anyList(), anyLong(), anyLong());
    }

    @Test
    void shouldRollbackIfSuccessUpdateDoesNotAffectOneRow() {
        when(documentMapper.updateById(any(KnowledgeDocumentDO.class))).thenAnswer(call -> {
            var document = call.<KnowledgeDocumentDO>getArgument(0);
            recordUpdate(document);
            return "success".equals(document.getStatus()) ? 0 : 1;
        });
        var response = service.uploadDocument(KB_ID, file);
        assertEquals("failed", response.getStatus());
        assertEquals("parsed", response.getParseStatus());
        assertEquals(0, response.getChunkCount());
        assertEquals(1, transactions.rollbacks);
        assertEquals("failed", updates.get(1).status());
    }

    @Test
    void shouldRejectKnowledgeBaseBeforeSavingFileWhenItDoesNotExist() {
        when(baseMapper.selectById(KB_ID)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.uploadDocument(KB_ID, file));
        verifyNoInteractions(storage);
        verify(documentMapper, never()).insert(any(KnowledgeDocumentDO.class));
    }

    @Test
    void shouldRecheckKnowledgeBaseUnderLockBeforeRegisteringPending() {
        when(baseMapper.selectActiveForUpdate(KB_ID)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.uploadDocument(KB_ID, file));
        verify(documentMapper, never()).insert(any(KnowledgeDocumentDO.class));
        verifyNoInteractions(parser, chunks);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void shouldNotWriteChunksIfKnowledgeBaseWasDeletedAfterParsing() {
        when(baseMapper.selectActiveForUpdate(KB_ID))
                .thenReturn(new KnowledgeBaseDO().setId(KB_ID), null);
        when(documentMapper.updateById(any(KnowledgeDocumentDO.class))).thenReturn(0);
        assertThrows(ClientException.class, () -> service.uploadDocument(KB_ID, file));
        verify(chunks, never()).saveChunks(anyList(), anyLong(), anyLong());
        assertEquals(2, transactions.rollbacks); // 最终写入检查失败 + 已删除文档无法记 failed
    }

    @Test
    void shouldHideUnexpectedInternalExceptionDetails() {
        when(parser.parse(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("D:/private/document-path"));
        var response = service.uploadDocument(KB_ID, file);
        assertEquals("failed", response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertFalse(response.getErrorMessage().contains("private"));
        assertFalse(updates.get(0).metadata().contains("private"));
    }

    @Test
    void shouldBindMetadataAsJsonbForUpdateById() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(KnowledgeDocumentMapper.class);
        MappedStatement statement = configuration.getMappedStatement(
                KnowledgeDocumentMapper.class.getName() + ".updateById");
        var document = new KnowledgeDocumentDO().setId(DOC_ID).setStatus("success")
                .setMetadata("{\"chunkCount\":1}");
        Map<String, Object> parameters = Map.of("et", document);
        BoundSql bound = statement.getBoundSql(parameters);
        assertTrue(bound.getParameterMappings().stream()
                .anyMatch(mapping -> mapping.getProperty().equals("et.metadata")
                        && mapping.getTypeHandler() instanceof JsonbTypeHandler));
        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameters, bound).setParameters(prepared);
        var value = org.mockito.ArgumentCaptor.forClass(PGobject.class);
        verify(prepared).setObject(anyInt(), value.capture());
        assertEquals("jsonb", value.getValue().getType());
        assertEquals(document.getMetadata(), value.getValue().getValue());
    }

    private void recordUpdate(KnowledgeDocumentDO document) {
        updates.add(new Update(document.getStatus(), document.getMetadata(),
                TransactionSynchronizationManager.isActualTransactionActive(), transactions.rollbacks));
    }

    private record Update(String status, String metadata, boolean inTransaction, int rollbacksBeforeUpdate) { }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
