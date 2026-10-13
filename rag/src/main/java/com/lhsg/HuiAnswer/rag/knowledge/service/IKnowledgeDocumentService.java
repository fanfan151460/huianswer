package com.lhsg.HuiAnswer.rag.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeChunkRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import org.springframework.web.multipart.MultipartFile;


public interface IKnowledgeDocumentService {

    /** 上传文档 */
    KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file);

    /** 分页查询知识库下的文档，包含禁用文档。 */
    IPage<KnowledgeDocumentRespDTO> listDocument(Long kbId, long current, long size);

    /** 修改启用状态：0 启用，1 禁用。 */
    void updateEnabled(Long kbId, Long docId, Integer enabled);

    /** 逻辑删除文档并物理清理片段；保留磁盘原文件。 */
    void deleteDocument(Long kbId, Long docId);

    /** 分页查看文档片段，不返回向量。 */
    IPage<KnowledgeChunkRespDTO> pageChunks(Long kbId, Long docId, long current, long size);

    /** 查询单个文档 */
    KnowledgeDocumentRespDTO getDocument(Long kbId, Long docId);

    /** 文档解析重试 */
    KnowledgeDocumentRespDTO retryDocument(Long kbId, Long docId);

    /** 异步解析文档分块向量化 */
    void consumeDocument(DocumentMsg message);
}
