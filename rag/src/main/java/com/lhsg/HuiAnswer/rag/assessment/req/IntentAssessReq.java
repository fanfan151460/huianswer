package com.lhsg.HuiAnswer.rag.assessment.req;

import lombok.Data;

@Data
public class IntentAssessReq {
    private String question;

    private Long conversationId;

}
