package com.planmate.domain.place.dto;

import com.planmate.domain.place.enums.PlaceCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// GET 쿼리 파라미터 바인딩(@ModelAttribute). page/size 미입력 시 기본값(1/20)을 채운다.
public record PlaceSearchRequest(
        @NotNull Integer destinationId,
        @NotNull PlaceCategory category,
        @Min(1) Integer page,
        @Min(1) @Max(20) Integer size
) {
    public PlaceSearchRequest {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 20;
        }
    }
}
