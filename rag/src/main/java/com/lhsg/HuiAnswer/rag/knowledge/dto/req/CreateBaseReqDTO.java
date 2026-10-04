package com.lhsg.HuiAnswer.rag.knowledge.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CreateBaseReqDTO {
    /** 知识库名称；创建时由数据库生成 ID，客户端只传名称。 */
    @NotBlank(message = "请对知识库命名")
    @Size(max = 128, message = "知识库名称不能超过128个字符")
    private String name;
}
