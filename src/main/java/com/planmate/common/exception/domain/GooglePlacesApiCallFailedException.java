package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class GooglePlacesApiCallFailedException extends PlanMateException {

    public GooglePlacesApiCallFailedException() {
        super(ErrorCode.GOOGLE_PLACES_CALL_FAILED);
    }
}
