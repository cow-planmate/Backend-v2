package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class TokenExpiredException extends PlanMateException {

    public TokenExpiredException() {
        super(ErrorCode.EXPIRED_TOKEN);
    }
}
