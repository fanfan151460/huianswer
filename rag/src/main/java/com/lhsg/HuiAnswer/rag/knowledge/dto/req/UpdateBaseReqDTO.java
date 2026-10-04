package com.lhsg.HuiAnswer.rag.knowledge.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UpdateBaseReqDTO {
    /** 修改的知识库 ID 从路径读取，正文只允许修改名称。 */
    @NotBlank(message = "请对知识库命名")
    @Size(max = 128, message = "知识库名称不能超过128个字符")
    private String name;
}
