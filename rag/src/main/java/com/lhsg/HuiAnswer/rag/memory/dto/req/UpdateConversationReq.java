package com.lhsg.HuiAnswer.rag.memory.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UpdateConversationReq {
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 128, message = "会话标题不能超过128个字符")
    private String title;
}