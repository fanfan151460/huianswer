package com.lhsg.HuiAnswer.rag.assessment.resp;

import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class RewriteAssessResp {
    private String question;

    private RewriteResultDTO rewriteResultDTO;

    private List<Reference> references = List.of();

    /** 检索失败时使用，区别于正常检索但没有命中资料。 */
    private String errorMessage;
}
