package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 重排序接口的成功响应，使用 Gson 从 JSON 解析。 */
@Data
@Accessors(chain = true)
public class RerankRespDTO {
    /** 本次请求的响应标识。 */
    private String id;

    /** 按相关性分数降序排列的结果；每个 index 仍对应原始 documents 的下标。 */
    private List<RerankResultDTO> results;
}
