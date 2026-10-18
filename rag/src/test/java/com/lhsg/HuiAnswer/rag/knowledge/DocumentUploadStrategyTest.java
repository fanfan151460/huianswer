package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.rag.knowledge.controller.KnowledgeDocumentController;
import com.lhsg.HuiAnswer.rag.knowledge.enums.SplitStrategy;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentUploadStrategyTest {
    private final IKnowledgeDocumentService service = mock(IKnowledgeDocumentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeDocumentController(service)).build();

    @Test
    void multipartStrategyIsConvertedToTheEnum() throws Exception {
        for (SplitStrategy strategy : SplitStrategy.values()) {
            mvc.perform(multipart("/api/knowledge-base/1/documents")
                            .file(file()).param("strategy", strategy.name()))
                    .andExpect(status().isOk());
            verify(service).uploadDocument(eq(1L), any(), eq(strategy));
        }
    }

    @Test
    void missingStrategyUsesOverlapForExistingClients() throws Exception {
        mvc.perform(multipart("/api/knowledge-base/1/documents").file(file()))
                .andExpect(status().isOk());
        verify(service).uploadDocument(eq(1L), any(), eq(SplitStrategy.OVERLAP));
    }

    @Test
    void unknownStrategyIsRejectedBeforeTheService() throws Exception {
        mvc.perform(multipart("/api/knowledge-base/1/documents")
                        .file(file()).param("strategy", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "rules.txt", "text/plain", "rules".getBytes());
    }
}
