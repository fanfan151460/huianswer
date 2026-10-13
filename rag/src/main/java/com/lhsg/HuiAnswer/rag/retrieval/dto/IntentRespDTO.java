package com.lhsg.HuiAnswer.rag.retrieval.dto;

import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 模型返回的单问题分类 JSON；下标由后端填入 IntentResultDTO。 */
@Data
@Accessors(chain = true)
public class IntentRespDTO {
    private IntentEnum intent;

    private List<Long> kbIds;
}
