package com.planmate.domain.place.dto;

import jakarta.validation.constraints.NotBlank;

// POST 바디. pageToken은 직전 응답의 nextPageToken을 그대로 왕복시킨다(서버 상태 없음).
public record PlaceTextSearchNextRequest(
        @NotBlank String query,
        @NotBlank String pageToken
) {
}
