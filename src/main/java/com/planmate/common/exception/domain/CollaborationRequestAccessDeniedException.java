package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class CollaborationRequestAccessDeniedException extends PlanMateException {

    public CollaborationRequestAccessDeniedException() {
        super(ErrorCode.COLLABORATION_REQUEST_ACCESS_DENIED);
    }
}
