package com.lhsg.HuiAnswer.rag.assessment.resp;

import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class IntentAssessResp {
    private String question;

    private RewriteResultDTO rewrite;

    private String intent;
}
