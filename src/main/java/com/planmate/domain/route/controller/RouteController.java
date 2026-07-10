package com.planmate.domain.route.controller;

import com.planmate.domain.route.dto.RouteRequest;
import com.planmate.domain.route.dto.RouteResponse;
import com.planmate.domain.route.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
@Tag(name = "Route", description = "OSRM 기반 길찾기 API")
public class RouteController {

    private final RouteService routeService;

    @Operation(summary = "경유지 기반 경로 조회", description = "경유지 목록을 받아 OSRM으로 실제 도로 경로를 계산합니다.")
    @PostMapping("/directions")
    public ResponseEntity<RouteResponse> getDirections(@RequestBody RouteRequest request) {
        return ResponseEntity.ok(routeService.getRoute(request.getWaypoints()));
    }
}
