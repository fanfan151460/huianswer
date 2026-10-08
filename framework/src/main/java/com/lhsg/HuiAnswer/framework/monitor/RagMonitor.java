package com.lhsg.HuiAnswer.framework.monitor;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class RagMonitor {

    /**
     * 拦截所有标了 @TimeAnnotation 的方法并记录耗时。
     */
    @Around(value = "@annotation(timeAnnotation)", argNames = "joinPoint,timeAnnotation")
    public Object ragMonitor(ProceedingJoinPoint joinPoint, TimeAnnotation timeAnnotation) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String type = timeAnnotation.method().isBlank() ? signature.getName() : timeAnnotation.method();
        String target = joinPoint.getTarget().getClass().getSimpleName() + "#" + signature.getName();

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.info("[{}] {} 耗时 {} ms", type, target, elapsedMs(start));
            return result;
        } catch (Throwable e) {
            // 异常本身交给上游的全局异常处理去记，这里只补一条耗时，避免同一异常被打两遍 error
            log.warn("[{}] {} 耗时 {} ms，抛出 {}: {}", type, target, elapsedMs(start),
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
