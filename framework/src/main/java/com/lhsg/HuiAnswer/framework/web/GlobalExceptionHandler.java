package com.lhsg.HuiAnswer.framework.web;


import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.lhsg.HuiAnswer.framework.result.errorcode.BaseErrorCode;
import com.lhsg.HuiAnswer.framework.exception.AbstractException;
import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Optional;

/**
 * 全局异常处理器
 *
 */
@Component
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截参数验证异常
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionStr = Optional.ofNullable(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    /**
     * 拦截应用内抛出的异常
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex.toString(), ex.getCause());
            return Results.failure(ex);
        }
        log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex.toString());
        return Results.failure(ex);
    }

    /**
     * 拦截未捕获异常
     */
    @ExceptionHandler(value = Throwable.class)
    public Result defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    /** JSON 格式错误或路径参数类型错误，应返回客户端错误。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public Result<Void> requestFormatException(HttpServletRequest request, Exception ex) {
        String message = ex instanceof HttpMessageNotReadableException
                ? "请求体必须是合法的JSON，且字段类型正确" : "请求参数类型不正确";
        log.warn("[{}] {} {}", request.getMethod(), getUrl(request), message);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    /** 文件大小和缺少 multipart 字段属于客户端错误。 */
    @ExceptionHandler({MaxUploadSizeExceededException.class, MissingServletRequestPartException.class})
    public Result<Void> uploadRequestException(Exception ex) {
        String message = ex instanceof MaxUploadSizeExceededException
                ? "上传文件超过大小限制" : "上传请求必须包含file字段";
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    private String getUrl(HttpServletRequest request) {
        if (StrUtil.isEmpty(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
