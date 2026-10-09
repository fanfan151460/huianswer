package com.lhsg.HuiAnswer.rag.admin.user;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UserRegisterReqDTO {
    private String username;
    private String password;
    private String confirmPassword;
}
