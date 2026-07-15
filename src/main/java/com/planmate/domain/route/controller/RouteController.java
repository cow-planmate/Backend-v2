package com.planmate.domain.route.controller;

import com.planmate.domain.route.dto.RouteRequest;
import com.planmate.domain.route.dto.RouteResponse;
import com.planmate.domain.route.dto.RouteTableRequest;
import com.planmate.domain.route.dto.RouteTableResponse;
import com.planmate.domain.route.dto.RouteTripRequest;
import com.planmate.domain.route.dto.RouteTripResponse;
import com.planmate.domain.route.dto.TransitLaneRequest;
import com.planmate.domain.route.dto.TransitLaneResponse;
import com.planmate.domain.route.dto.TransitRouteRequest;
import com.planmate.domain.route.dto.TransitRouteResponse;
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
@Tag(name = "Route", description = "길찾기(경로) 관련 API")
public class RouteController {

    private final RouteService routeService;

    @Operation(summary = "경유지 기반 경로 조회", description = "경유지 목록을 받아 OSRM으로 실제 도로 경로를 계산합니다.")
    @PostMapping("/directions")
    public ResponseEntity<RouteResponse> getDirections(@RequestBody RouteRequest request) {
        return ResponseEntity.ok(routeService.getRoute(request.getWaypoints()));
    }

    @Operation(summary = "장소 쌍별 소요시간/거리 매트릭스 조회", description = "좌표 목록의 모든 쌍에 대한 소요 시간(초)과 이동 거리(m) 매트릭스를 반환합니다.")
    @PostMapping("/table")
    public ResponseEntity<RouteTableResponse> getTable(@RequestBody RouteTableRequest request) {
        return ResponseEntity.ok(routeService.getTable(request.getWaypoints(), request.getProfile()));
    }

    @Operation(summary = "방문 순서 최적화", description = "첫 좌표를 출발지로 고정하고 나머지 좌표의 최적 방문 순서를 계산합니다.")
    @PostMapping("/trip")
    public ResponseEntity<RouteTripResponse> getTrip(@RequestBody RouteTripRequest request) {
        return ResponseEntity.ok(routeService.getTrip(request.getWaypoints(), request.getProfile(), request.isRoundtrip()));
    }

    @Operation(summary = "대중교통 경로 조회", description = "두 지점 간 대중교통(지하철/버스) 최적 경로의 소요 시간과 요금을 반환합니다.")
    @PostMapping("/transit")
    public ResponseEntity<TransitRouteResponse> getTransit(@RequestBody TransitRouteRequest request) {
        return ResponseEntity.ok(routeService.getTransit(request.getFrom(), request.getTo()));
    }

    @Operation(summary = "대중교통 경로 폴리라인 조회", description = "선택한 대중교통 경로(mapObj)의 노선별 지도 폴리라인 좌표를 반환합니다.")
    @PostMapping("/transit/lane")
    public ResponseEntity<TransitLaneResponse> getTransitLane(@RequestBody TransitLaneRequest request) {
        return ResponseEntity.ok(routeService.getTransitLane(request.getMapObj()));
    }
}
