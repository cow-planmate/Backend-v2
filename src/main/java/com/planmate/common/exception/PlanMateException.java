package com.planmate.common.exception;

import lombok.Getter;

@Getter
public class PlanMateException extends RuntimeException {

    private final ErrorCode errorCode;

    public PlanMateException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public PlanMateException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
