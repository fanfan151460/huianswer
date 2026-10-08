package com.lhsg.HuiAnswer.rag.retrieval.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 发给重排序模型的请求体，使用 Gson 转为 JSON。 */
@Data
@Accessors(chain = true)
public class RerankReqDTO {
    /** 重排序模型名称，例如 BAAI/bge-reranker-v2-m3。 */
    private String model;

    /** 用户的原始问题。 */
    private String query;

    /** 候选片段原文，顺序与本地 candidates 列表一致，便于用响应 index 找回来源。 */
    private List<String> documents;

    /** 最多返回多少个结果，需大于 0；Java 字段 topN 对应接口的 top_n。 */
    @SerializedName("top_n")
    private Integer topN;

    /** 是否在响应中返回原文；默认 false，本地已有候选文本，可以通过 index 取回。 */
    @SerializedName("return_documents")
    private boolean returnDocuments = false;
}
