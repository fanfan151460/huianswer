package com.lhsg.HuiAnswer.rag.chat.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

// TODO 以后模型健康度测评可能会用到
@Data
@Accessors(chain = true)
public class LLMChatResp {
    /** 模型回答文本 */
    private String answer;

    /** 选择的模型 */
    private String model;

    /** 回答消耗 token */
    private int tokens;

    /** 结束原因 */
    private String finishReason;

    /** 参考资料 */
    private List<NeedChunksResp> needChunks;
}
