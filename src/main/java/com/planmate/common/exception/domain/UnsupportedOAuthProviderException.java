package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class UnsupportedOAuthProviderException extends PlanMateException {

    public UnsupportedOAuthProviderException() {
        super(ErrorCode.OAUTH_UNSUPPORTED_PROVIDER);
    }
}
