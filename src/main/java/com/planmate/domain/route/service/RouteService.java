package com.planmate.domain.route.service;

import com.planmate.common.externalAPI.OdsayTransit;
import com.planmate.common.externalAPI.OdsayTransit.TransitLaneResult;
import com.planmate.common.externalAPI.OdsayTransit.TransitResult;
import com.planmate.common.externalAPI.OsrmRouting;
import com.planmate.common.externalAPI.OsrmRouting.RoutingResult;
import com.planmate.common.externalAPI.OsrmRouting.TableResult;
import com.planmate.common.externalAPI.OsrmRouting.TripResult;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.RouteResponse;
import com.planmate.domain.route.dto.RouteTableResponse;
import com.planmate.domain.route.dto.RouteTripLegDto;
import com.planmate.domain.route.dto.RouteTripResponse;
import com.planmate.domain.route.dto.TransitLaneResponse;
import com.planmate.domain.route.dto.TransitRouteResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RouteService {

    private final OsrmRouting osrmRouting;
    private final OdsayTransit odsayTransit;

    public RouteResponse getRoute(List<RoutePointDto> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return new RouteResponse(new ArrayList<>(), 0, 0);
        }

        RoutingResult result = osrmRouting.getRoute(waypoints);
        if (result != null) {
            return new RouteResponse(result.path(), result.distance(), result.duration());
        }

        // OSRM 실패 시: 입력 경유지를 그대로 경로로 반환 (프론트엔드가 직선으로 표시)
        return new RouteResponse(waypoints, 0, 0);
    }

    /**
     * 좌표 쌍별 소요시간/거리 매트릭스를 조회한다. 실패하면 빈 매트릭스를 반환한다.
     */
    public RouteTableResponse getTable(List<RoutePointDto> waypoints, String profile) {
        String normalized = OsrmRouting.normalizeProfile(profile);

        TableResult result = osrmRouting.getTable(waypoints, normalized);
        if (result != null) {
            return new RouteTableResponse(result.durations(), result.distances(), normalized);
        }
        return new RouteTableResponse(new ArrayList<>(), new ArrayList<>(), normalized);
    }

    /**
     * 방문 순서를 최적화한다. 실패하면 빈 응답을 반환한다.
     */
    public RouteTripResponse getTrip(List<RoutePointDto> waypoints, String profile, boolean roundtrip) {
        TripResult result = osrmRouting.getTrip(waypoints, profile, roundtrip);
        if (result != null) {
            List<RouteTripLegDto> legs = new ArrayList<>();
            for (OsrmRouting.TripLeg leg : result.legs()) {
                legs.add(new RouteTripLegDto(leg.distance(), leg.duration()));
            }
            return new RouteTripResponse(result.visitOrder(), result.totalDistance(), result.totalDuration(), legs);
        }
        return new RouteTripResponse(new ArrayList<>(), 0, 0, new ArrayList<>());
    }

    /**
     * 두 지점 간 대중교통 경로를 조회한다. 실패하면 사용 불가 응답을 반환한다.
     */
    public TransitRouteResponse getTransit(RoutePointDto from, RoutePointDto to) {
        TransitResult result = odsayTransit.getTransitPath(from, to);
        if (result == null) {
            return new TransitRouteResponse(false, "대중교통 경로 조회에 실패했습니다", new ArrayList<>(), 0, 0, 0);
        }
        return new TransitRouteResponse(result.available(), result.message(), result.routes(),
                result.busCount(), result.subwayCount(), result.subwayBusCount());
    }

    /**
     * 선택한 대중교통 경로(mapObj)의 지도 폴리라인을 조회한다. 실패/없음 시 빈 목록을 반환한다.
     */
    public TransitLaneResponse getTransitLane(String mapObj) {
        TransitLaneResult result = odsayTransit.getLane(mapObj);
        if (result == null) {
            return new TransitLaneResponse(new ArrayList<>());
        }
        return new TransitLaneResponse(result.lanes());
    }
}
