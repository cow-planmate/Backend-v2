package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PlanInvalidBlockDateException extends PlanMateException {

    public PlanInvalidBlockDateException() {
        super(ErrorCode.PLAN_INVALID_BLOCK_DATE);
    }
}
