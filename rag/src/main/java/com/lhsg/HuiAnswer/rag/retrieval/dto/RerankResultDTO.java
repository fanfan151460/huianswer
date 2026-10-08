package com.lhsg.HuiAnswer.rag.retrieval.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.experimental.Accessors;

/** 重排序响应 results 中的一项。 */
@Data
@Accessors(chain = true)
public class RerankResultDTO {
    /** 原始 documents 数组的下标，从 0 开始；据此取得 candidates 中的 Reference。 */
    private Integer index;

    /** 相关性分数，越大越靠前；不代表回答正确的概率。 */
    @SerializedName("relevance_score")
    private Double relevanceScore;
}
