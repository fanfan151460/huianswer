package com.lhsg.HuiAnswer.rag.admin.intercepter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOGIN_USER;

@RequiredArgsConstructor
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UsersContext.removeUserDTO();
        String token = request.getHeader("token");
        String userName = request.getHeader("username");
        if (StrUtil.isBlank(token)) {
            throw new ClientException("用户未登录");
        }
        Object cacheValue = stringRedisTemplate.opsForHash().get(String.format(HUI_ANSWER_LOGIN_USER, userName), token);
        if (Objects.isNull(cacheValue)) {
            throw new ClientException("用户登录信息异常!");
        }
        UserDTO user = JSONUtil.toBean((String) cacheValue, UserDTO.class);
        UsersContext.setUserDTO(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UsersContext.removeUserDTO();
    }

}
