package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class OAuthSignupSessionNotFoundException extends PlanMateException {

    public OAuthSignupSessionNotFoundException() {
        super(ErrorCode.OAUTH_SIGNUP_SESSION_NOT_FOUND);
    }
}
