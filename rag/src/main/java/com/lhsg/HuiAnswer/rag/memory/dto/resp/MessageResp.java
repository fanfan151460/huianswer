package com.lhsg.HuiAnswer.rag.memory.dto.resp;

import com.lhsg.HuiAnswer.rag.chat.dto.resp.NeedChunksResp;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 一条历史消息；前端按 role 展示问题或回答。 */
@Data
@Accessors(chain = true)
public class MessageResp {
    private Long id;

    private Integer turnNo;

    /** user / assistant。 */
    private String role;

    private String content;

    /** success / failed，失败回答也需要在历史中展示。 */
    private String status;

    private List<NeedChunksResp> sources = List.of();

    private String model;

    private Integer totalTokens;

    private String finishReason;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
