package com.lhsg.HuiAnswer.rag.memory.dto.req;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AddConversationReq {
    private long kbId;

    private String question;
}
