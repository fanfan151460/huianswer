package com.lhsg.HuiAnswer.rag.knowledge.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CreateBaseReqDTO {
    /** 知识库名称；创建时由数据库生成 ID，客户端可传名称和描述。 */
    @NotBlank(message = "请对知识库命名")
    @Size(max = 128, message = "知识库名称不能超过128个字符")
    private String name;

    /** 覆盖的业务主题；兼容旧请求，不传时保存为空字符串。 */
    @Size(max = 1000, message = "知识库描述不能超过1000个字符")
    private String description;
}
