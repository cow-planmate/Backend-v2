package com.planmate.domain.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RoutePointDto {

    @Schema(description = "위도", example = "37.5665")
    private double lat;

    @Schema(description = "경도", example = "126.9780")
    private double lng;

    @Schema(description = "장소 ID (선택) — 있으면 캐시 키로 우선 사용되어 재사용 범위가 넓어진다. 없으면 좌표 기준으로 캐싱")
    private String placeId;
}
