package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class InvalidTokenException extends PlanMateException {

    public InvalidTokenException() {
        super(ErrorCode.INVALID_TOKEN);
    }
}
