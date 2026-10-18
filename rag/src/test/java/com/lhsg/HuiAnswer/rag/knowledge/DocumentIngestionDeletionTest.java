package com.lhsg.HuiAnswer.rag.knowledge;

import com.google.gson.Gson;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.DocumentTypeValidator;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.document.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.enums.SplitStrategy;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
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
    private final DocumentProducer producer = mock(DocumentProducer.class);
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentServiceImpl(bases, documents, chunks, storage, parser, mock(DocumentTypeValidator.class), new Gson(),
                chunkService, transaction, producer);
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

    @Test
    void uploadPersistsTheSelectedStrategyAndPassesItToTheMessage() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UsersContext.setUserDTO(user);
        try {
            when(bases.selectById(1L)).thenReturn(new KnowledgeBaseDO().setId(1L));
            when(documents.insert(any(KnowledgeDocumentDO.class))).thenAnswer(call -> {
                KnowledgeDocumentDO stored = call.getArgument(0);
                stored.setId(101L);
                when(documents.selectById(101L)).thenReturn(stored);
                return 1;
            });
            MockMultipartFile file = new MockMultipartFile("file", "rules.txt", "text/plain", "rules".getBytes());

            service.uploadDocument(1L, file, SplitStrategy.RECURSIVE);

            ArgumentCaptor<KnowledgeDocumentDO> inserted = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
            verify(documents).insert(inserted.capture());
            assertEquals("RECURSIVE", new Gson().fromJson(inserted.getValue().getMetadata(),
                    com.google.gson.JsonObject.class).get("splitStrategy").getAsString());
            verify(producer).send(argThat(msg -> msg.getKbId().equals(1L)
                    && msg.getDocId().equals(101L) && msg.getStrategy() == SplitStrategy.RECURSIVE));
        } finally {
            UsersContext.removeUserDTO();
        }
    }

    @Test
    void manualRetryKeepsTheStrategyStoredAtUpload() throws Exception {
        KnowledgeDocumentDO failed = document(0).setStatus("failed")
                .setMetadata("{\"splitStrategy\":\"RECURSIVE\",\"errorMessage\":\"failed\"}");
        when(bases.selectById(1L)).thenReturn(new KnowledgeBaseDO().setId(1L));
        when(documents.selectById(101L)).thenReturn(failed);

        service.retryDocument(1L, 101L);

        verify(producer).send(argThat(msg -> msg.getStrategy() == SplitStrategy.RECURSIVE));
        assertEquals("RECURSIVE", new Gson().fromJson(failed.getMetadata(),
                com.google.gson.JsonObject.class).get("splitStrategy").getAsString());
    }

    @Test
    void retryOfOldDocumentUsesOverlap() throws Exception {
        when(bases.selectById(1L)).thenReturn(new KnowledgeBaseDO().setId(1L));
        when(documents.selectById(101L)).thenReturn(document(0).setStatus("failed"));

        service.retryDocument(1L, 101L);

        verify(producer).send(argThat(msg -> msg.getStrategy() == SplitStrategy.OVERLAP));
    }

    private void prepareParsing() throws Exception {
        when(storage.open("unused-test-path")).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(parser.parse(any(InputStream.class), eq("rules.txt"), eq("txt")))
                .thenReturn(new ParsedDocument("full text", "text/plain"));
        when(chunkService.toChunks("full text", SplitStrategy.OVERLAP)).thenReturn(List.of("full text"));
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
