package com.lhsg.HuiAnswer.rag.chat.build.req;

import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class Messages {
    private String question;

    private List<Reference> references;

    private SearchSummaryMemoryDTO history;
}
