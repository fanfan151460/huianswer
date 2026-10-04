package com.lhsg.HuiAnswer.framework.duplicate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static com.lhsg.HuiAnswer.framework.constant.Constant.NODUPLICATE_SUBMIT;
import static com.lhsg.HuiAnswer.framework.constant.Constant.USER_HEADER;

/**
 * 防止重复提交切面
 * <p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
@Component
@Aspect
public class NoDuplicateAspect {

    private final StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(noDuplicateSubmit)")
    public Object noDuplicate(ProceedingJoinPoint joinPoint, NoDuplicateSubmit noDuplicateSubmit) throws Throwable {
        String key = String.format(NODUPLICATE_SUBMIT, getServletPath(), getCurrentUserId(), calcArgsMD5(joinPoint));

        Boolean setIfAbsent = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", noDuplicateSubmit.expire(), noDuplicateSubmit.timeUnit());
        if (!Boolean.TRUE.equals(setIfAbsent)) {
            throw new ClientException(noDuplicateSubmit.msg());
        }
        try {
            return joinPoint.proceed();
        } finally {
            // 方法执行完（成功或异常）释放 key，避免正常完成后仍被误判为重复提交
            stringRedisTemplate.delete(key);
        }
    }

    /**
     * @return 获取当前线程上下文 ServletPath
     */
    private String getServletPath() {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return sra.getRequest().getServletPath();
    }

    /**
     * @return 获取当前 username
     */
    private String getCurrentUserId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String userName = attrs.getRequest().getHeader(USER_HEADER);
        if (StrUtil.isBlank(userName)) {
            return "empty-user";
        }
        return userName;
    }

    /**
     * @return joinPoint md5
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        return DigestUtil.md5Hex(JSON.toJSONBytes(joinPoint.getArgs()));
    }
}
