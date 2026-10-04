package com.lhsg.HuiAnswer.rag.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("t_knowledge_base")
public class KnowledgeBaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库名称 */
    private String name;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 0 正常，1 删除 */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
