package com.planmate.domain.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "인접한 두 경유지 사이 한 구간의 상세 정보")
public class RouteLegDto {

    @Schema(description = "출발 경유지 인덱스")
    private int fromIndex;

    @Schema(description = "도착 경유지 인덱스")
    private int toIndex;

    @Schema(description = "구간 이동 거리(m)")
    private long distance;

    @Schema(description = "구간 소요 시간(초)")
    private long duration;

    @Schema(description = "구간의 턴바이턴 안내 단계 목록")
    private List<RouteStepDto> steps;

    @Schema(description = "구간의 대안 경로 목록 (없으면 빈 배열)")
    private List<RouteAlternativeDto> alternatives;
}
