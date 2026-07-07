package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PlanMemberNotFoundException extends PlanMateException {

    public PlanMemberNotFoundException() {
        super(ErrorCode.PLAN_MEMBER_NOT_FOUND);
    }
}
