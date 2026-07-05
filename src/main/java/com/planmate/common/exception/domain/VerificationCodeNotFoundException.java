package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class VerificationCodeNotFoundException extends PlanMateException {

    public VerificationCodeNotFoundException() {
        super(ErrorCode.VERIFICATION_CODE_NOT_FOUND);
    }
}
