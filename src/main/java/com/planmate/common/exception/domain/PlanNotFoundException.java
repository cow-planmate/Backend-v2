package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PlanNotFoundException extends PlanMateException {

    public PlanNotFoundException() {
        super(ErrorCode.PLAN_NOT_FOUND);
    }
}
