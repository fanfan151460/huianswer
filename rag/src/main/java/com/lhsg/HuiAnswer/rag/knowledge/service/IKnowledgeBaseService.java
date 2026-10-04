package com.lhsg.HuiAnswer.rag.knowledge.service;

import com.lhsg.HuiAnswer.rag.knowledge.dto.req.CreateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.UpdateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeBaseRespDTO;

import java.util.List;

public interface IKnowledgeBaseService {
    KnowledgeBaseRespDTO createBase(CreateBaseReqDTO createBaseReqDTO);

    List<KnowledgeBaseRespDTO> listBase();

    KnowledgeBaseRespDTO getBase(Long id);

    KnowledgeBaseRespDTO updateBase(Long id, UpdateBaseReqDTO updateBaseReqDTO);

    void deleteBase(Long id);
}
