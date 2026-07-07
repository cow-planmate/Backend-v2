package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class CollaborationRequestDuplicatePendingException extends PlanMateException {

    public CollaborationRequestDuplicatePendingException() {
        super(ErrorCode.COLLABORATION_REQUEST_DUPLICATE_PENDING);
    }
}
