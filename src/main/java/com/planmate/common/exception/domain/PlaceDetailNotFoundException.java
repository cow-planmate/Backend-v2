package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

/**
 * 장소 상세 조회 대상이 없음. {@code place_list}에 {@code content_id}가 없거나,
 * 있어도 상세 API가 다루는 타입(12/14/32/39)이 아닌 경우(축제·코스·레포츠·쇼핑) → 404(PLACE_006).
 * project_ai/04_기능/ai/05_장소상세_API_설계.md §10.
 */
public class PlaceDetailNotFoundException extends PlanMateException {

    public PlaceDetailNotFoundException() {
        super(ErrorCode.PLACE_DETAIL_NOT_FOUND);
    }
}
