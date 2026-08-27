package com.planmate.domain.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "본 경로 대신 선택할 수 있는 대안 경로")
public class RouteAlternativeDto {

    @Schema(description = "대안 경로 좌표 목록")
    private List<RoutePointDto> path;

    @Schema(description = "총 이동 거리(m)")
    private long distance;

    @Schema(description = "총 소요 시간(초)")
    private long duration;
}
