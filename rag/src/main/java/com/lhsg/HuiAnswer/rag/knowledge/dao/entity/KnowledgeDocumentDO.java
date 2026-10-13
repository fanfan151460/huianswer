package com.lhsg.HuiAnswer.rag.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lhsg.HuiAnswer.rag.knowledge.handler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "t_knowledge_document", autoResultMap = true)
public class KnowledgeDocumentDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属知识库 */
    private Long kbId;

    /** 上传者的管理员 ID，在上传请求中记录，MQ 消费时保持不变 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long createdBy;

    /** 文件名 */
    private String docName;

    /** 原文件保存位置 */
    private String filePath;

    /** 可选 JSON 元数据 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metadata;

    /** pending / running / success / failed */
    private String status;

    /** 0 启用，1 禁用；独立于处理状态和逻辑删除。 */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
