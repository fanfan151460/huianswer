package com.lhsg.HuiAnswer.rag.knowledge.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 返回调用方需要的信息，避免直接暴露数据库实体中的删除等内部字段。 */
@Data
@Accessors(chain = true)
public class KnowledgeBaseRespDTO {
    private Long id;
    private String name;

    /** 创建者的管理员 ID */
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
