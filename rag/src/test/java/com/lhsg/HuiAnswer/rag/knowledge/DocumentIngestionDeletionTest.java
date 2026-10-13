package com.lhsg.HuiAnswer.rag.knowledge;

import com.google.gson.Gson;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.document.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIngestionDeletionTest {
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
    private final LocalDocumentStorage storage = mock(LocalDocumentStorage.class);
    private final TikaDocumentParser parser = mock(TikaDocumentParser.class);
    private final IKnowledgeChunkService chunkService = mock(IKnowledgeChunkService.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentServiceImpl(bases, documents, chunks, storage, parser, new Gson(),
                chunkService, transaction, mock(DocumentProducer.class));
        when(bases.selectActiveForUpdate(1L)).thenReturn(new KnowledgeBaseDO().setId(1L));
        when(documents.updateById(any(KnowledgeDocumentDO.class))).thenReturn(1);
        when(transaction.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(status);
        });
        doAnswer(call -> {
            Consumer<TransactionStatus> callback = call.getArgument(0);
            callback.accept(status);
            return null;
        }).when(transaction).executeWithoutResult(any());
    }

    @Test
    void deletedBeforeConsumptionIsAcknowledgedWithoutParsing() {
        when(documents.selectById(101L)).thenReturn(null);

        assertDoesNotThrow(() -> service.consumeDocument(message()));

        verifyNoInteractions(storage, parser, chunkService, chunks);
        verify(documents, never()).updateById(any(KnowledgeDocumentDO.class));
    }

    @Test
    void deletedWhileParsingDoesNotRestoreChunks() throws Exception {
        when(documents.selectById(101L)).thenReturn(document(0), null);
        prepareParsing();

        service.consumeDocument(message());

        verify(chunkService, never()).saveChunks(anyList());
        verify(documents, times(1)).updateById(any(KnowledgeDocumentDO.class));
        verify(documents, never()).insert(any(KnowledgeDocumentDO.class));
    }

    @Test
    void disablingDuringProcessingSurvivesTheSuccessUpdate() throws Exception {
        when(documents.selectById(101L)).thenReturn(document(0), document(1));
        prepareParsing();

        service.consumeDocument(message());

        ArgumentCaptor<KnowledgeDocumentDO> updates = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documents, times(2)).updateById(updates.capture());
        KnowledgeDocumentDO completed = updates.getAllValues().get(1);
        assertEquals("success", completed.getStatus());
        assertEquals(1, completed.getEnabled());
        verify(chunkService).saveChunks(anyList());
    }

    private void prepareParsing() throws Exception {
        when(storage.open("unused-test-path")).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(parser.parse(any(InputStream.class), eq("rules.txt"), eq("txt")))
                .thenReturn(new ParsedDocument("full text", "text/plain"));
        when(chunkService.toChunks("full text")).thenReturn(List.of("full text"));
        when(chunkService.prepareChunks(List.of("full text"), 1L, 101L))
                .thenReturn(List.of(new KnowledgeChunkDO().setKbId(1L).setDocId(101L)
                        .setChunkIndex(0).setContent("full text")));
    }

    private KnowledgeDocumentDO document(int enabled) {
        return new KnowledgeDocumentDO().setId(101L).setKbId(1L).setEnabled(enabled)
                .setStatus("pending").setMetadata("{}").setDocName("rules.txt")
                .setFilePath("unused-test-path");
    }

    private DocumentMsg message() {
        return new DocumentMsg().setKbId(1L).setDocId(101L);
    }
}
