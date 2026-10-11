package com.lhsg.HuiAnswer.rag.memory.dto.search;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SearchMemoryDTO {
    /** 已有消息轮次，BeanUtil 复制历史时一并带出。 */
    private Integer turnNo;

    /** 角色 */
    private String role;

    /** 文本内容 */
    private String content;
}
