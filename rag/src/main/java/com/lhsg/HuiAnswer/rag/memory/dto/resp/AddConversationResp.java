package com.lhsg.HuiAnswer.rag.memory.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AddConversationResp {
    /** 创建会话id */
    private long conversationId;
}
