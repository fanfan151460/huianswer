package com.lhsg.HuiAnswer.rag.retrieval.dto;

import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 后端内部执行结果：每个子问题保留自己的意图、资料和失败信息。 */
@Data
@Accessors(chain = true)
public class SubQuestionResultDTO {
    private Integer index;

    private String question;

    private IntentEnum intent;

    private List<Reference> references = List.of();

    /** 检索失败时使用，区别于正常检索但没有命中资料。 */
    private String errorMessage;

    /** 以下为本轮过程记录，不改变检索分支。 */
    private List<Long> kbIds = List.of();
    private List<String> kbNames = List.of();
    private boolean routeFallback;
    private String routeErrorType;
    private double routeMs;
    /** skipped / success / empty / error。 */
    private String searchStatus = "skipped";
    private String searchErrorType;
    private Double searchMs;
}
