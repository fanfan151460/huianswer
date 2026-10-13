package com.lhsg.HuiAnswer.rag.chat.dto.req;

import lombok.Data;

@Data
public class QuestionReq {
    /** 用户问题 */
    private String question;


    /** 会话id；不传表示新会话。 */
    private Long conversationId;

}
