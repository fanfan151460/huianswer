package com.lhsg.HuiAnswer.rag.chat.dto;

import lombok.Data;

@Data
public class QuestionReq {
    /** 用户问题 */
    private String question;

    /** 选择知识库 */
    private long kbId;
}
