package com.lhsg.HuiAnswer.rag.knowledge.service;

import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IKnowledgeDocumentService {

    /** 上传文档 */
    KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file);

    /** 查询知识库下的所有文档 */
    List<KnowledgeDocumentRespDTO> listDocument(Long kbId);

    /** 查询单个文档 */
    KnowledgeDocumentRespDTO getDocument(Long kbId, Long docId);

    /** 文档解析重试 */
    KnowledgeDocumentRespDTO retryDocument(Long kbId, Long docId);

    /** 异步解析文档分块向量化 */
    void consumeDocument(DocumentMsg message);
}
