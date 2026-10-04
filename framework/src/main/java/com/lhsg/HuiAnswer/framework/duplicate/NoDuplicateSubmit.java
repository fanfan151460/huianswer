package com.lhsg.HuiAnswer.framework.duplicate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoDuplicateSubmit {

    /**
     * 重复提交时的提示信息
     */
    String msg() default "请勿在短时间内重复提交";

    /**
     * 拦截窗口时长（作为兜底过期时间，防止进程崩溃导致 key 残留）
     */
    long expire() default 3;

    /**
     * 拦截窗口时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
