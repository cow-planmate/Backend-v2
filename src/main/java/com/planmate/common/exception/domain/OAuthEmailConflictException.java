package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class OAuthEmailConflictException extends PlanMateException {

    public OAuthEmailConflictException() {
        super(ErrorCode.OAUTH_EMAIL_CONFLICT);
    }
}
