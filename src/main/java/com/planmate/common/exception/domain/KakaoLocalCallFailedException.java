package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class KakaoLocalCallFailedException extends PlanMateException {

    public KakaoLocalCallFailedException() {
        super(ErrorCode.KAKAO_LOCAL_CALL_FAILED);
    }
}
