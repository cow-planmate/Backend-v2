package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class AuthenticationRequiredException extends PlanMateException {

    public AuthenticationRequiredException() {
        super(ErrorCode.UNAUTHORIZED);
    }
}
