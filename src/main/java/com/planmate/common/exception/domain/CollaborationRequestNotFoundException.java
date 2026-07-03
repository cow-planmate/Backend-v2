package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class CollaborationRequestNotFoundException extends PlanMateException {

    public CollaborationRequestNotFoundException() {
        super(ErrorCode.COLLABORATION_REQUEST_NOT_FOUND);
    }
}
