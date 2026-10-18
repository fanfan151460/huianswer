package com.lhsg.HuiAnswer.rag.assessment.req;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RewriteAssessReq {
    private String question;

    private Long conversationId;

    private String choose;
}
