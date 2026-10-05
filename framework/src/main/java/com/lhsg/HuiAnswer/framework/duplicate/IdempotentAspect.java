package com.lhsg.HuiAnswer.framework.duplicate;

import com.lhsg.HuiAnswer.framework.base.MsgWrapper;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final IdempotentHandler idempotentHandler;

    @Around("@annotation(Idempotent)")
    public Object msgIdempotent(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        MsgWrapper<?> wrapper = args[0] instanceof MsgWrapper ? (MsgWrapper<?>) args[0] : null;
        String keys = "";
        if (wrapper != null) {
            keys = wrapper.getKeys();
        }
        if (idempotentHandler.success(keys)) {
            return null;
        }
        if (!idempotentHandler.consuming(keys)) {
            throw new ServiceException("消息幂等 key 处于消费中重试消费");
        }
        Object result;
        try {
            result = joinPoint.proceed(args);
            idempotentHandler.successConsume(keys);
        } catch (Throwable e) {
            idempotentHandler.failConsume(keys);
            log.error("消息消费异常，消息id:{}，幂等逻辑执行失败", keys);
            throw e;
        }
        return result;
    }
}
