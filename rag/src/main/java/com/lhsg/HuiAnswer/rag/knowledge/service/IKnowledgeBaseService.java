package com.lhsg.HuiAnswer.rag.knowledge.service;

import com.lhsg.HuiAnswer.rag.knowledge.dto.req.CreateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.UpdateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeBaseRespDTO;

import java.util.List;

public interface IKnowledgeBaseService {
    /** 创建知识库 */
    KnowledgeBaseRespDTO createBase(CreateBaseReqDTO createBaseReqDTO);

    /** 查询知识库 */
    List<KnowledgeBaseRespDTO> listBase();

    /** 知识库并返回响应实体 */
    KnowledgeBaseRespDTO getBase(Long id);

    /** 修改知识库 */
    KnowledgeBaseRespDTO updateBase(Long id, UpdateBaseReqDTO updateBaseReqDTO);

    /** 删除知识库（物理删除，高危操作） */
    void deleteBase(Long id);
}
