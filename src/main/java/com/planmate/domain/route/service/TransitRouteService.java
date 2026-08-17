package com.planmate.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.externalAPI.OdsayTransit;
import com.planmate.common.externalAPI.OdsayTransit.TransitLaneResult;
import com.planmate.common.externalAPI.OdsayTransit.TransitResult;
import com.planmate.domain.route.cache.RouteCacheKeyGenerator;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.TransitLaneResponse;
import com.planmate.domain.route.dto.TransitRouteResponse;
import com.planmate.domain.route.enums.RouteCacheMode;
import com.planmate.domain.route.repository.RouteCacheRepository;
import com.planmate.domain.route.repository.RouteTransitLaneCacheRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransitRouteService {

    private static final long TRANSIT_CACHE_TTL_DAYS = 14;

    private final OdsayTransit odsayTransit;
    private final RouteCacheRepository routeCacheRepository;
    private final RouteTransitLaneCacheRepository laneCacheRepository;
    private final ObjectMapper objectMapper;
    private final RouteCacheSupport routeCacheSupport;

    /**
     * 두 지점 간 대중교통 경로를 조회한다. 캐시에 있으면 ODsay를 호출하지 않는다.
     */
    @Transactional
    public TransitRouteResponse getTransit(RoutePointDto from, RoutePointDto to) {
        String fromKey = RouteCacheKeyGenerator.key(from);
        String toKey = RouteCacheKeyGenerator.key(to);

        Optional<TransitRouteResponse> cached = routeCacheSupport.readValidCache(
                routeCacheRepository.findByFromKeyAndToKeyAndMode(fromKey, toKey, RouteCacheMode.TRANSIT),
                TransitRouteResponse.class, "대중교통");
        if (cached.isPresent()) {
            return cached.get();
        }

        TransitResult result = odsayTransit.getTransitPath(from, to);
        TransitRouteResponse response = (result == null)
                ? new TransitRouteResponse(false, "대중교통 경로 조회에 실패했습니다", new ArrayList<>(), 0, 0, 0)
                : new TransitRouteResponse(result.available(), result.message(), result.routes(),
                        result.busCount(), result.subwayCount(), result.subwayBusCount());

        // 조회 실패/경로 없음은 캐싱하지 않는다 — 일시적 장애를 "경로 없음"으로 영구 고정시키지 않기 위해.
        if (result != null && result.available()) {
            routeCacheSupport.writeCache(fromKey, toKey, RouteCacheMode.TRANSIT, response, TRANSIT_CACHE_TTL_DAYS, "대중교통");
        }
        return response;
    }

    /**
     * 선택한 대중교통 경로(mapObj)의 지도 폴리라인을 조회한다. mapObj는 실측(2026-08-11)으로
     * 동일 경로 재조회 시 값이 바뀌지 않는 것을 확인해 캐싱 대상으로 확정했다.
     */
    @Transactional
    public TransitLaneResponse getTransitLane(String mapObj) {
        if (mapObj == null || mapObj.isBlank()) {
            return new TransitLaneResponse(new ArrayList<>());
        }

        Optional<TransitLaneResponse> cached = routeCacheSupport.readValidLaneCache(mapObj);
        if (cached.isPresent()) {
            return cached.get();
        }

        TransitLaneResult result = odsayTransit.getLane(mapObj);
        TransitLaneResponse response = (result == null)
                ? new TransitLaneResponse(new ArrayList<>())
                : new TransitLaneResponse(result.lanes());

        if (result != null && !result.lanes().isEmpty()) {
            try {
                String payload = objectMapper.writeValueAsString(response);
                laneCacheRepository.upsert(mapObj, payload, LocalDateTime.now().plusDays(TRANSIT_CACHE_TTL_DAYS));
            } catch (Exception e) {
                log.warn("폴리라인 캐시 저장 실패(API 결과는 정상 반환): {}", e.getMessage());
            }
        }
        return response;
    }
}
