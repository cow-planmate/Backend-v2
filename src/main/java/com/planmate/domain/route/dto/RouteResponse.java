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
}
