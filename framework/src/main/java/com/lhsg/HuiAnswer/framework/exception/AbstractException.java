package com.lhsg.HuiAnswer.framework.exception;

import cn.hutool.core.util.StrUtil;
import com.lhsg.HuiAnswer.framework.errorcode.IErrorCode;
import lombok.Getter;

import java.util.Optional;

@Getter
public abstract class AbstractException extends RuntimeException {

    public final String errorCode;

    public final String errorMessage;

    public AbstractException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable);
        this.errorCode = errorCode.code();
        this.errorMessage = Optional
                .ofNullable(!StrUtil.isEmpty(message) ? message : null)
                .orElse(errorCode.message());
    }
}