package com.lhsg.HuiAnswer.rag.admin.service;

import com.lhsg.HuiAnswer.rag.admin.dto.UserLoginReqDTO;
import com.lhsg.HuiAnswer.rag.admin.dto.UserRespDTO;

public interface IUserService {

    UserRespDTO login(UserLoginReqDTO userLoginReqDTO);
}
