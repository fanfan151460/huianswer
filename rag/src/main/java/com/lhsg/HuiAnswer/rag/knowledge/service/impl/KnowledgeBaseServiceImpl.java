package com.lhsg.HuiAnswer.rag.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.CreateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.UpdateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeBaseRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements IKnowledgeBaseService {
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @Override
    public KnowledgeBaseRespDTO createBase(CreateBaseReqDTO createBaseReqDTO) {
        // TODO 用户鉴权：当前学习阶段尚未接入用户身份。
        String name = validateName(createBaseReqDTO == null ? null : createBaseReqDTO.getName());
        KnowledgeBaseDO knowledgeBaseDO = new KnowledgeBaseDO().setName(name);
        if (knowledgeBaseMapper.insert(knowledgeBaseDO) != 1) {
            throw new ServiceException("创建知识库失败");
        }
        // @TableId(AUTO) 回填 ID，审计字段由 MetaObjectHandler 填充。
        return toRespDTO(knowledgeBaseDO);
    }

    @Override
    public List<KnowledgeBaseRespDTO> listBase() {
        // @TableLogic 自动补上 deleted = 0。
        return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery()
                        .orderByDesc(KnowledgeBaseDO::getId))
                .stream().map(this::toRespDTO).toList();
    }

    @Override
    public KnowledgeBaseRespDTO getBase(Long id) {
        return toRespDTO(requireBase(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseRespDTO updateBase(Long id, UpdateBaseReqDTO updateBaseReqDTO) {
        String name = validateName(updateBaseReqDTO == null ? null : updateBaseReqDTO.getName());
        KnowledgeBaseDO knowledgeBaseDO = requireBase(id).setName(name);
        // updateById 带有 deleted = 0 条件；若记录已被并发删除，更新条数为 0。
        if (knowledgeBaseMapper.updateById(knowledgeBaseDO) != 1) {
            throw new ClientException("知识库不存在或已被删除");
        }
        return toRespDTO(knowledgeBaseDO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBase(Long id) {
        if (id == null || id <= 0) {
            throw new ClientException("知识库ID必须是正整数");
        }
        // 与文档上传锁同一行：拿锁后再清理，避免漏掉并发导入的文档。
        if (knowledgeBaseMapper.selectActiveForUpdate(id) == null) {
            throw new ClientException("知识库不存在");
        }
        // 1. 物理清理片段：同一行保存原文和向量，删除时一起清理。
        knowledgeChunkMapper.deletePhysicallyByKbId(id);
        // 2. 保留文档记录，标记为删除。
        documentMapper.markDeletedByKbId(id, LocalDateTime.now());
        // 3. 删除知识库。任一步抛异常，上面的操作都由事务回滚。
        if (knowledgeBaseMapper.deleteById(id) != 1) {
            throw new ClientException("知识库不存在或已被删除");
        }
    }

    private KnowledgeBaseDO requireBase(Long id) {
        if (id == null || id <= 0) {
            throw new ClientException("知识库ID必须是正整数");
        }
        KnowledgeBaseDO knowledgeBaseDO = knowledgeBaseMapper.selectById(id);
        if (knowledgeBaseDO == null) {
            throw new ClientException("知识库不存在");
        }
        return knowledgeBaseDO;
    }

    private String validateName(String name) {
        // 业务层也校验，其他 Service 直接调用时同样得到明确错误。
        if (StrUtil.isBlank(name)) {
            throw new ClientException("请对知识库命名");
        }
        String normalized = name.strip();
        if (normalized.length() > 128) {
            throw new ClientException("知识库名称不能超过128个字符");
        }
        return normalized;
    }

    private KnowledgeBaseRespDTO toRespDTO(KnowledgeBaseDO knowledgeBaseDO) {
        return new KnowledgeBaseRespDTO().setId(knowledgeBaseDO.getId())
                .setName(knowledgeBaseDO.getName()).setCreateTime(knowledgeBaseDO.getCreateTime())
                .setUpdateTime(knowledgeBaseDO.getUpdateTime());
    }
}
