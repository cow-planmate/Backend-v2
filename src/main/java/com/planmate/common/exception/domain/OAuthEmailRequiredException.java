package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class OAuthEmailRequiredException extends PlanMateException {

    public OAuthEmailRequiredException() {
        super(ErrorCode.OAUTH_EMAIL_REQUIRED);
    }
}
