package com.lhsg.HuiAnswer.rag.admin;

import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.admin.dto.UserLoginReqDTO;
import com.lhsg.HuiAnswer.rag.admin.dto.UserRespDTO;
import com.lhsg.HuiAnswer.rag.admin.service.IUserService;
import com.lhsg.HuiAnswer.rag.admin.user.UserRegisterReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    @PostMapping("/api/hui-answer/login")
    public Result<UserRespDTO> login(@RequestBody UserLoginReqDTO dto) {
        UserRespDTO userRespDTO = userService.login(dto);
        return Results.success(userRespDTO);
    }

    @PostMapping("/api/hui-answer/register")
    public Result<Void> register(@RequestBody UserRegisterReqDTO dto) {
        userService.register(dto);
        return Results.success();
    }
}
