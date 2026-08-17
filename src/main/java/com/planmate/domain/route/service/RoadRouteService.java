package com.planmate.domain.route.service;

import com.planmate.common.externalAPI.OsrmRouting;
import com.planmate.common.externalAPI.OsrmRouting.RoutingResult;
import com.planmate.common.externalAPI.OsrmRouting.TripResult;
import com.planmate.domain.route.cache.RouteCacheKeyGenerator;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.RouteResponse;
import com.planmate.domain.route.dto.RouteTableResponse;
import com.planmate.domain.route.dto.RouteTripLegDto;
import com.planmate.domain.route.dto.RouteTripResponse;
import com.planmate.domain.route.enums.RouteCacheMode;
import com.planmate.domain.route.repository.RouteCacheRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoadRouteService {

    private static final long ROAD_CACHE_TTL_DAYS = 30;

    private final OsrmRouting osrmRouting;
    private final RouteCacheRepository routeCacheRepository;
    private final RouteCacheSupport routeCacheSupport;

    /**
     * 경유지 기반 도로 경로를 조회한다. 인접 쌍 단위로 캐시를 확인/채우고, 받은 경로 조각을
     * 경계점 중복 제거하며 이어 붙인다. 어느 한 쌍이라도 실패하면(캐시 미스 + API 호출도 실패)
     * 기존과 동일하게 입력 경유지를 그대로 반환한다(프론트엔드가 직선으로 표시).
     */
    @Transactional
    public RouteResponse getRoute(List<RoutePointDto> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            return new RouteResponse(new ArrayList<>(), 0, 0);
        }

        List<RoutePointDto> path = new ArrayList<>();
        long totalDistance = 0;
        long totalDuration = 0;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            RoutingResult leg = getOrFetchRoadPair(waypoints.get(i), waypoints.get(i + 1), RouteCacheMode.DRIVING);
            if (leg == null) {
                return new RouteResponse(waypoints, 0, 0);
            }
            if (!leg.path().isEmpty()) {
                if (!path.isEmpty()) {
                    path.remove(path.size() - 1); // 이전 구간의 마지막 점 == 다음 구간의 첫 점
                }
                path.addAll(leg.path());
            }
            totalDistance += leg.distance();
            totalDuration += leg.duration();
        }

        return new RouteResponse(path, totalDistance, totalDuration);
    }

    /**
     * 좌표 쌍별 소요시간/거리 매트릭스를 조회한다. 인접 칸({@code [i][i+1]})만 실제로 채우고
     * 나머지는 null로 둔다 — 호출 측이 인접 칸만 사용하기 때문이며, 이렇게 하면 각 인접 쌍을
     * {@link #getRoute}와 동일한 캐시 행으로 공유할 수 있다.
     */
    @Transactional
    public RouteTableResponse getTable(List<RoutePointDto> waypoints, String profile) {
        String normalized = OsrmRouting.normalizeProfile(profile);
        if (waypoints == null || waypoints.size() < 2) {
            return new RouteTableResponse(new ArrayList<>(), new ArrayList<>(), normalized);
        }

        RouteCacheMode mode = RouteCacheMode.fromProfile(normalized);
        int n = waypoints.size();
        List<List<Double>> durations = emptyMatrix(n);
        List<List<Double>> distances = emptyMatrix(n);

        for (int i = 0; i < n - 1; i++) {
            RoutingResult leg = getOrFetchRoadPair(waypoints.get(i), waypoints.get(i + 1), mode);
            if (leg != null) {
                durations.get(i).set(i + 1, (double) leg.duration());
                distances.get(i).set(i + 1, (double) leg.distance());
            }
        }

        return new RouteTableResponse(durations, distances, normalized);
    }

    /**
     * 방문 순서를 최적화한다. 실패하면 빈 응답을 반환한다.
     * (캐싱 대상 아님 — 프론트에서 아직 호출하지 않고, 조건이 좌표 쌍이 아니라 waypoint 집합
     * 전체라 route_cache의 인접 쌍 키 구조에 맞지 않는다)
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
     * 도로(driving/foot) 인접 쌍 하나를 캐시에서 찾고, 없으면 OSRM {@code /route}를 호출해 채운다.
     * {@code table}과 {@code directions}가 이 메서드를 공유하므로, 같은 (from,to,mode) 쌍이면
     * 두 엔드포인트가 캐시 행을 그대로 공유한다 — table 미스 시에도 OSRM {@code /table}이 아니라
     * {@code /route}를 호출하는 이유가 이것이다(그래야 path까지 채워져 directions가 재사용 가능).
     */
    private RoutingResult getOrFetchRoadPair(RoutePointDto from, RoutePointDto to, RouteCacheMode mode) {
        String fromKey = RouteCacheKeyGenerator.key(from);
        String toKey = RouteCacheKeyGenerator.key(to);

        Optional<RoutingResult> cached = routeCacheSupport.readValidCache(
                routeCacheRepository.findByFromKeyAndToKeyAndMode(fromKey, toKey, mode),
                RoutingResult.class, "도로");
        if (cached.isPresent()) {
            return cached.get();
        }

        RoutingResult result = osrmRouting.getRoute(List.of(from, to));
        if (result == null) {
            return null;
        }
        routeCacheSupport.writeCache(fromKey, toKey, mode, result, ROAD_CACHE_TTL_DAYS, "도로");
        return result;
    }

    private List<List<Double>> emptyMatrix(int n) {
        List<List<Double>> matrix = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Double> row = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                row.add(null);
            }
            matrix.add(row);
        }
        return matrix;
    }
}
