package com.lhsg.HuiAnswer.rag.admin.service;

import com.lhsg.HuiAnswer.rag.admin.dto.UserLoginReqDTO;
import com.lhsg.HuiAnswer.rag.admin.dto.UserRespDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UserRegisterReqDTO;

public interface IUserService {

    UserRespDTO login(UserLoginReqDTO userLoginReqDTO);

    void register(UserRegisterReqDTO loginReqDTO);
}
