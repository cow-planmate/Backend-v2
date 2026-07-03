package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PlanAccessDeniedException extends PlanMateException {

    public PlanAccessDeniedException() {
        super(ErrorCode.PLAN_ACCESS_DENIED);
    }
}
