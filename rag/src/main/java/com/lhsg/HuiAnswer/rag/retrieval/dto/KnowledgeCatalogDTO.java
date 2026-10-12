package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 发给路由模型的平铺目录，只包含知识库 ID、名称和业务描述。 */
@Data
@Accessors(chain = true)
public class KnowledgeCatalogDTO {
    /** 知识库id */
    private Long kbId;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;
}
