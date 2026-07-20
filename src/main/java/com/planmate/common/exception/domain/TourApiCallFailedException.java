package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class TourApiCallFailedException extends PlanMateException {

    public TourApiCallFailedException() {
        super(ErrorCode.TOUR_API_CALL_FAILED);
    }
}
