package com.lhsg.HuiAnswer.rag.retrieval.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 一次模型调用返回的完整改写文本和可独立理解的子问题。 */
@Data
@Accessors(chain = true)
public class RewriteResultDTO {
    /** 完整输入的最小必要改写，保留全部需求及其条件。 */
    private String rewrittenQuestion;

    /** 不拆分时只有一项，与 rewrittenQuestion 一致。 */
    private List<String> subQuestions;

    /** 后端记录是否因异常退回原问题，供评测排查。 */
    private boolean fallback;

    private String errorType;

    /** 实际采用的改写模型；全部失败时记录最后尝试的模型。 */
    private String model;

    /** 是否尝试了备用模型；备用成功时 fallback 仍为 false。 */
    private boolean backupUsed;

    /** 主模型失败的原因，便于区分正常主调用和备用救回的结果。 */
    private String primaryErrorType;
    private String primaryErrorMessage;
}
