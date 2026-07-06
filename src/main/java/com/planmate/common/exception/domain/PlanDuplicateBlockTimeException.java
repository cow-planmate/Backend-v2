package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PlanDuplicateBlockTimeException extends PlanMateException {

    public PlanDuplicateBlockTimeException() {
        super(ErrorCode.PLAN_DUPLICATE_BLOCK_TIME);
    }
}
