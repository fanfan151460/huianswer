package com.lhsg.HuiAnswer.rag.admin.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UserLoginReqDTO {
    public String username;

    public String password;
}
