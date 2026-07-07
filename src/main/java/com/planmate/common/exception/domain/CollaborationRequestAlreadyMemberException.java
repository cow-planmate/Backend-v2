package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class CollaborationRequestAlreadyMemberException extends PlanMateException {

    public CollaborationRequestAlreadyMemberException() {
        super(ErrorCode.COLLABORATION_REQUEST_ALREADY_MEMBER);
    }
}
