package com.lhsg.HuiAnswer.rag.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lhsg.HuiAnswer.rag.knowledge.handler.PgVectorTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "t_knowledge_chunk", autoResultMap = true)
public class KnowledgeChunkDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long docId;

    /** 文档内片段序号，从 0 开始 */
    private Integer chunkIndex;

    /** 交给聊天模型的原文 */
    private String content;

    /** 用于相似度计算的向量，数据库列为 vector(4096) */
    @TableField(typeHandler = PgVectorTypeHandler.class)
    private float[] embedding;

    /** chunk关键词 */
    private String searchText;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
