package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class ProfilePrivateException extends PlanMateException {

    public ProfilePrivateException() {
        super(ErrorCode.PROFILE_PRIVATE);
    }
}
