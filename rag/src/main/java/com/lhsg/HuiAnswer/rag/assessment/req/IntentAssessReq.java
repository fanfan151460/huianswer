package com.lhsg.HuiAnswer.rag.assessment.req;

import lombok.Data;

@Data
public class IntentAssessReq {
    private String question;

    private Long conversationId;

    /** 复用旧会话时，截取这条原用户消息之前的历史；不传则按 question 定位。 */
    private String historyBeforeQuestion;

}
