package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;
import java.util.List;

/** 批量路由 JSON；最外层使用对象以适配 json_object 模式。 */
@Data
@Accessors(chain = true)
public class IntentRespDTO {
    private List<IntentResultDTO> items;
}
