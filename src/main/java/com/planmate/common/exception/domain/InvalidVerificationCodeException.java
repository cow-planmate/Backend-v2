package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class InvalidVerificationCodeException extends PlanMateException {

    public InvalidVerificationCodeException() {
        super(ErrorCode.INVALID_VERIFICATION_CODE);
    }
}
