package com.lhsg.HuiAnswer.rag.memory.dto.search;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 问答上下文：旧历史摘要和仍需保留原文的成功问答。 */
@Data
@Accessors(chain = true)
public class SearchSummaryMemoryDTO {
    private String summary = "";

    private List<SearchMemoryDTO> memory = List.of();
}
