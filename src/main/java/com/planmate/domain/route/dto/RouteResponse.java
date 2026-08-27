package com.planmate.domain.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RouteResponse {

    @Schema(description = "경로 좌표 목록")
    private List<RoutePointDto> path;

    @Schema(description = "총 이동 거리(m)")
    private long distance;

    @Schema(description = "총 소요 시간(초)")
    private long duration;

    @Schema(description = "적용된 이동 수단 프로필", example = "driving")
    private String profile;

    @Schema(description = "구간(인접 경유지 쌍)별 상세 정보 — 턴바이턴 안내와 대안 경로를 포함한다")
    private List<RouteLegDto> legs;
}
