package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class UserNotFoundException extends PlanMateException {

    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND);
    }
}
