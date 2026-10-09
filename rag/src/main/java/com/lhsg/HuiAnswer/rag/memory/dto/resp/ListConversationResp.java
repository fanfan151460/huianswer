package com.lhsg.HuiAnswer.rag.memory.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class ListConversationResp {
    private long id;

    private String title;

    private Long kbId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
