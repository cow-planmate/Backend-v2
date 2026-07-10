package com.planmate.domain.route.service;

import com.planmate.common.externalAPI.OsrmRouting;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.RouteResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RouteService {

    private final OsrmRouting osrmRouting;

    public RouteResponse getRoute(List<RoutePointDto> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return new RouteResponse(new ArrayList<>(), 0, 0);
        }

        OsrmRouting.RoutingResult result = osrmRouting.getRoute(waypoints);
        if (result != null) {
            return new RouteResponse(result.path(), result.distance(), result.duration());
        }

        // OSRM 실패 시: 입력 경유지를 그대로 경로로 반환 (프론트엔드가 직선으로 표시)
        return new RouteResponse(waypoints, 0, 0);
    }
}
