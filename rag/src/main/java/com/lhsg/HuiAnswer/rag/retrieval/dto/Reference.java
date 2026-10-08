package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Reference {
    /** 资料来源 */
    private String docName;
    private long docId;

    /** 检索片段文本 */
    private String content;

    /** 片段id */
    private long chunkId;

    /** 融合分数：RRF 的 Σ 1/(k+rank)，越大越靠前 */
    private double score;

    /** 重排序分数 */
    private double rerankScore;
}
