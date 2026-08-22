package com.planmate.domain.place.dto;

import jakarta.validation.constraints.NotBlank;

// GET 쿼리 파라미터 바인딩(@ModelAttribute). destinationId는 선택 — 있으면 그 목적지 좌표로
// 검색 결과에 소프트 가중치를 준다(제한 아님). project_ai/04_기능/place/07_키워드검색_API_상세설계.md §5-3.
public record PlaceTextSearchRequest(
        @NotBlank String query,
        Integer destinationId
) {
}
