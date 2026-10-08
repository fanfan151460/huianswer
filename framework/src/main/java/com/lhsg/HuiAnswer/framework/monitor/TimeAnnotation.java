package com.lhsg.HuiAnswer.framework.monitor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要统计耗时的方法，由 RagMonitor 切面读取。
 * 例：{@code @TimeAnnotation(method = "向量检索")}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TimeAnnotation {

    /**
     * 日志里的方法类型，留空则用被拦截的方法名
     */
    String method() default "";
}
