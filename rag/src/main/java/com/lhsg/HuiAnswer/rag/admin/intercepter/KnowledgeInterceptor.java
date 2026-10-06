package com.lhsg.HuiAnswer.rag.admin.intercepter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.rmi.ServerException;
import java.util.Objects;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOGIN_USER;

/**
 * 管理员登录鉴权
 */
@Component
@RequiredArgsConstructor
public class KnowledgeInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UsersContext.removeUserDTO();
        String token = request.getHeader("admin-token");
        String userName = request.getHeader("userName");
        if (StrUtil.isBlank(token)) {
            throw new ServerException("请联系管理员修改知识库相关信息!");
        }
        Object cacheValue = stringRedisTemplate.opsForHash().get(String.format(HUI_ANSWER_LOGIN_USER, userName), token);
        if (Objects.isNull(cacheValue)) {
            throw new ServerException("请联系管理员修改知识库相关信息!");
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
