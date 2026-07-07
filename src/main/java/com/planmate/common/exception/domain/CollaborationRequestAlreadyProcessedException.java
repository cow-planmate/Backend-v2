package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class CollaborationRequestAlreadyProcessedException extends PlanMateException {

    public CollaborationRequestAlreadyProcessedException() {
        super(ErrorCode.COLLABORATION_REQUEST_ALREADY_PROCESSED);
    }
}
