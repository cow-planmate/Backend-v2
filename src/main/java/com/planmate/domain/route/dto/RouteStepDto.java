package com.planmate.domain.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "턴바이턴 안내 한 단계")
public class RouteStepDto {

    @Schema(description = "이 단계의 이동 거리(m)")
    private long distance;

    @Schema(description = "이 단계의 소요 시간(초)")
    private long duration;

    @Schema(description = "도로명 (OSM에 없으면 null)")
    private String name;

    @Schema(description = "한국어 안내 문구", example = "세종대로 방면으로 우회전")
    private String instruction;

    @Schema(description = "OSRM maneuver type 원본 값", example = "turn")
    private String maneuverType;

    @Schema(description = "OSRM maneuver modifier 원본 값", example = "right")
    private String maneuverModifier;

    @Schema(description = "회전교차로 진출 번호 (해당 없으면 null)")
    private Integer exit;

    @Schema(description = "안내 지점 좌표")
    private RoutePointDto location;
}
