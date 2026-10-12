package com.lhsg.HuiAnswer.rag.retrieval.dto;

import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import lombok.Data;
import lombok.experimental.Accessors;
import java.util.List;

/** 子问题的路由结果；子问题原文由后端按 index 从输入列表取回。 */
@Data
@Accessors(chain = true)
public class IntentResultDTO {
    private Integer index;

    private IntentEnum intent;

    private List<Long> kbIds;
}
