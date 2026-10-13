package com.lhsg.HuiAnswer.rag.knowledge.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UpdateBaseReqDTO {
    /** 修改的知识库 ID 从路径读取，正文允许修改名称和描述。 */
    @NotBlank(message = "请对知识库命名")
    @Size(max = 128, message = "知识库名称不能超过128个字符")
    private String name;

    /** 不传时保留已有描述，空字符串表示清空。 */
    @Size(max = 1000, message = "知识库描述不能超过1000个字符")
    private String description;
}
