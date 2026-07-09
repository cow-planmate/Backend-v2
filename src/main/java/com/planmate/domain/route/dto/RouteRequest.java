package com.planmate.domain.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RouteRequest {

    @Schema(description = "경유지 목록 (순서대로)")
    private List<RoutePointDto> waypoints;
}
