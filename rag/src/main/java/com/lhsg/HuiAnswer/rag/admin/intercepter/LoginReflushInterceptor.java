package com.lhsg.HuiAnswer.rag.admin.intercepter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOGIN_USER;

/**
 * 登录状态时间刷新拦截器
 */
@Component
@RequiredArgsConstructor
public class LoginReflushInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userName = request.getHeader("userName");
        stringRedisTemplate.expire(String.format(HUI_ANSWER_LOGIN_USER, userName), 1, TimeUnit.HOURS);
        return true;
    }

}
