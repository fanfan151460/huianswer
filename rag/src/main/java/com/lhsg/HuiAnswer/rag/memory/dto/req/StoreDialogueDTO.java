package com.lhsg.HuiAnswer.rag.memory.dto.req;

import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 保存本轮助手消息；只记录成功或失败的结束态。 */
@Data
@Accessors(chain = true)
public class StoreDialogueDTO {
    private Long conversationId;

    /** 登录用户 ID */
    private Long userId;

    /** 保存用户问题时确定，回答沿用同一个轮次。 */
    private Integer turnNo;

    private String content;

    private String status;

    /** 问答参考资料 */
    private List<NeedChunksResp> sources = List.of();

    private String model;

    private Integer totalTokens;

    private String finishReason;

    private String errorMessage;
}