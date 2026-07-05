package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class OAuthAlreadyRegisteredException extends PlanMateException {

    public OAuthAlreadyRegisteredException() {
        super(ErrorCode.OAUTH_ALREADY_REGISTERED);
    }
}
