package com.lhsg.HuiAnswer.rag.chat.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StopGenerationReq {
    @NotBlank(message = "请传递本次生成ID")
    @Size(min = 36, max = 36, message = "生成ID格式不正确")
    private String generationId;
}