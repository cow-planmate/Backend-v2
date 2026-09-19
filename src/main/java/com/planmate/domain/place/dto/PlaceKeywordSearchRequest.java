package com.planmate.domain.place.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// GET 쿼리 파라미터 바인딩(@ModelAttribute). page/size 미입력 시 기본값(1/20)을 채운다.
// place/10_장소검색_캐시DB_설계.md §5.
public record PlaceKeywordSearchRequest(
        @NotBlank @Size(min = 2, max = 50) String query,
        @Min(1) Integer page,
        @Min(1) @Max(20) Integer size
) {
    public PlaceKeywordSearchRequest {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 20;
        }
    }
}
