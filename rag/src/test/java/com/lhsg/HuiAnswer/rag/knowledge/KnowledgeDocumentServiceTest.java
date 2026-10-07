package com.lhsg.HuiAnswer.rag.knowledge;

import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证这次改动的失败处理和删除边界，不启动中间件或调用模型。 */
class KnowledgeDocumentServiceTest {
    private KnowledgeBaseMapper bases;
    private KnowledgeDocumentMapper documents;
    private LocalDocumentStorage storage;
    private TikaDocumentParser parser;
    private IKnowledgeChunkService chunks;
    private DocumentProducer producer;
    private PlatformTransactionManager transactions;
    private KnowledgeDocumentServiceImpl service;
    private KnowledgeDocumentDO document;
    private final DocumentMsg msg = new DocumentMsg().setKbId(7L).setDocId(11L);

    @BeforeEach
    void setUp() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UsersContext.setUserDTO(user);
        bases = mock(KnowledgeBaseMapper.class);
        documents = mock(KnowledgeDocumentMapper.class);
        storage = mock(LocalDocumentStorage.class);
        parser = mock(TikaDocumentParser.class);
        chunks = mock(IKnowledgeChunkService.class);
        producer = mock(DocumentProducer.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = new KnowledgeDocumentServiceImpl(bases, documents, storage, parser, new Gson(),
                chunks, new TransactionTemplate(transactions), producer);
        KnowledgeBaseDO base = new KnowledgeBaseDO().setId(7L);
        when(bases.selectById(7L)).thenReturn(base);
        when(bases.selectActiveForUpdate(7L)).thenReturn(base);
        document = new KnowledgeDocumentDO().setId(11L).setKbId(7L).setDocName("guide.txt")
                .setFilePath("memory/guide.txt").setStatus("pending").setMetadata("{}");
        when(documents.selectById(11L)).thenAnswer(call -> document);
        when(documents.insert(any(KnowledgeDocumentDO.class))).thenAnswer(call -> {
            document = call.<KnowledgeDocumentDO>getArgument(0).setId(11L);
            return 1;
        });
        when(documents.updateById(any(KnowledgeDocumentDO.class))).thenReturn(1);
        when(storage.save(anyLong(), any(), anyString())).thenReturn("memory/guide.txt");
        when(storage.open(anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(parser.parse(any(), anyString(), anyString())).thenReturn(new ParsedDocument("资料正文", "text/plain"));
        when(chunks.toChunks(anyString())).thenReturn(List.of("资料正文"));
        when(chunks.prepareChunks(anyList(), eq(7L), eq(11L)))
                .thenReturn(List.of(new KnowledgeChunkDO().setKbId(7L).setDocId(11L)));
    }

    @Test
    void uploadShouldReturnPendingWithoutParsing() {
        var response = service.uploadDocument(7L,
                new MockMultipartFile("file", "guide.txt", "text/plain", new byte[]{1}));
        assertEquals("pending", response.getStatus());
        assertEquals(11L, response.getId());
        assertEquals(1L, document.getCreatedBy());
        assertEquals(1L, response.getCreatedBy());
        verify(producer).send(msg);
        verifyNoInteractions(parser, chunks);
    }

    @AfterEach
    void clearUser() {
        UsersContext.removeUserDTO();
    }

    @Test
    void uploadWithoutLoginShouldNotSaveFile() {
        UsersContext.removeUserDTO();
        assertThrows(ClientException.class, () -> service.uploadDocument(7L,
                new MockMultipartFile("file", "guide.txt", "text/plain", new byte[]{1})));
        verifyNoInteractions(storage, producer);
    }

    @Test
    void consumeWithoutRequestContextShouldKeepUploader() {
        document.setCreatedBy(1L);
        UsersContext.removeUserDTO();

        service.consumeDocument(msg);

        assertEquals(1L, document.getCreatedBy());
        assertEquals("success", document.getStatus());
    }

    @Test
    void sendFailureShouldKeepDocumentIdAndExposeFailedStatus() {
        doThrow(new ServiceException("消息发送失败")).when(producer).send(any(DocumentMsg.class));
        var response = service.uploadDocument(7L,
                new MockMultipartFile("file", "guide.txt", "text/plain", new byte[]{1}));
        assertEquals(11L, response.getId());
        assertEquals("failed", response.getStatus());
        assertFalse(response.getErrorMessage().isBlank());
        verifyNoInteractions(parser, chunks);
    }

    @Test
    void failedWriteShouldRollbackAndRecordFailureInAnotherTransaction() {
        doThrow(new IllegalStateException("数据库写入失败")).when(chunks).saveChunks(anyList());
        assertThrows(ServiceException.class, () -> service.consumeDocument(msg));
        assertEquals("failed", document.getStatus());
        assertTrue(document.getMetadata().contains("errorMessage"));
        verify(transactions).rollback(any(TransactionStatus.class));
        verify(transactions, times(2)).commit(any(TransactionStatus.class));
    }

    @Test
    void deletionDuringEmbeddingShouldPreventFinalChunkWrites() {
        when(chunks.prepareChunks(anyList(), eq(7L), eq(11L))).thenAnswer(call -> {
            when(bases.selectActiveForUpdate(7L)).thenReturn(null);
            return List.of(new KnowledgeChunkDO().setKbId(7L).setDocId(11L));
        });
        assertThrows(ServiceException.class, () -> service.consumeDocument(msg));
        verify(chunks, never()).saveChunks(anyList());
        verify(documents, times(1)).updateById(any(KnowledgeDocumentDO.class));
    }
}
