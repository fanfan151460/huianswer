package com.lhsg.HuiAnswer.rag.knowledge.service;

import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;

public interface IKnowledgeDocumentService {

    KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file);

    List<KnowledgeDocumentRespDTO> listDocument(Long kbId);

    KnowledgeDocumentRespDTO getDocument(Long kbId, Long docId);

    KnowledgeDocumentRespDTO retryDocument(Long kbId, Long docId);

    void consumeDocument(DocumentMsg message);
}
