package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class References {
    /** 资料来源 */
    private String docName;

    /** 检索片段文本 */
    private String content;

    // 近似度
}
