package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class KakaoLocalNotConfiguredException extends PlanMateException {

    public KakaoLocalNotConfiguredException() {
        super(ErrorCode.KAKAO_LOCAL_NOT_CONFIGURED);
    }
}
