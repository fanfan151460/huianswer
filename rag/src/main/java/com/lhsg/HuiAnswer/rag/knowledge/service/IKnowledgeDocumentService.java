package com.lhsg.HuiAnswer.rag.knowledge.service;

import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IKnowledgeDocumentService {

    KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file);

    List<KnowledgeDocumentRespDTO> listDocument(Long kbId);
}
