package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class OAuthInvalidIdTokenException extends PlanMateException {

    public OAuthInvalidIdTokenException() {
        super(ErrorCode.OAUTH_INVALID_ID_TOKEN);
    }
}
