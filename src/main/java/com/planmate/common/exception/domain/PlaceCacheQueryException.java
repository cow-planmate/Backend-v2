package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

/**
 * 추천 장소 캐시 DB(tourdata) 조회 실패. {@code PlaceCacheRepository}에서 {@code DataAccessException}을
 * 잡아 이 예외로 래핑한다 → 503(PLACE_005). 미처리 예외(500)로 새지 않게 한다.
 * project_ai/04_기능/ai/03_추천장소_캐시전환_설계.md §4-8.
 */
public class PlaceCacheQueryException extends PlanMateException {

    public PlaceCacheQueryException(Throwable cause) {
        super(ErrorCode.PLACE_RECOMMENDATION_UNAVAILABLE);
        initCause(cause);
    }
}
