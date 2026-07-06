package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class DestinationNotFoundException extends PlanMateException {

    public DestinationNotFoundException() {
        super(ErrorCode.DESTINATION_NOT_FOUND);
    }
}
