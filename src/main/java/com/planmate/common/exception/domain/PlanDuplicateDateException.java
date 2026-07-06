package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PlanDuplicateDateException extends PlanMateException {

    public PlanDuplicateDateException() {
        super(ErrorCode.PLAN_DUPLICATE_DATE);
    }
}
