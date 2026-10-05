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

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

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
        Object[] args = joinPoint.getArgs().clone();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof MultipartFile file) {
                // MultipartFile 不能直接 JSON 序列化：它的 Resource.getURL() 会抛异常。
                // 用文件属性和内容摘要参与判重，不把 InputStream/Resource 放进 JSON。
                try (InputStream input = file.getInputStream()) {
                    args[i] = Map.of("filename", String.valueOf(file.getOriginalFilename()),
                            "size", file.getSize(), "md5", DigestUtil.md5Hex(input));
                } catch (IOException e) {
                    throw new ClientException("读取上传文件失败");
                }
            }
        }
        return DigestUtil.md5Hex(JSON.toJSONBytes(args));
    }
}
