package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class References {
    /** 资料来源 */
    private String docName;
    private long docId;

    /** 检索片段文本 */
    private String content;

    /** 片段id */
    private long chunkId;

    // 近似度
}
