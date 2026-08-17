package com.planmate.domain.route.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.route.dto.TransitLaneResponse;
import com.planmate.domain.route.entity.RouteCache;
import com.planmate.domain.route.enums.RouteCacheMode;
import com.planmate.domain.route.repository.RouteCacheRepository;
import com.planmate.domain.route.repository.RouteTransitLaneCacheRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 도로(RoadRouteService)와 대중교통(TransitRouteService.getTransit)이 함께 쓰는
 * route_cache 테이블에 대한 read/write/직렬화 헬퍼.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteCacheSupport {

    private final RouteCacheRepository routeCacheRepository;
    private final RouteTransitLaneCacheRepository laneCacheRepository;
    private final ObjectMapper objectMapper;

    public <T> Optional<T> readValidCache(Optional<RouteCache> cacheEntry, Class<T> type, String logLabel) {
        return cacheEntry
                .filter(c -> c.getExpiredAt().isAfter(LocalDateTime.now()))
                .flatMap(c -> deserialize(c.getResponsePayload(), type, logLabel));
    }

    public Optional<TransitLaneResponse> readValidLaneCache(String mapObj) {
        return laneCacheRepository.findByMapObj(mapObj)
                .filter(c -> c.getExpiredAt().isAfter(LocalDateTime.now()))
                .flatMap(c -> deserialize(c.getResponsePayload(), TransitLaneResponse.class, "폴리라인"));
    }

    public void writeCache(String fromKey, String toKey, RouteCacheMode mode, Object payloadObject,
            long ttlDays, String logLabel) {
        try {
            String payload = objectMapper.writeValueAsString(payloadObject);
            routeCacheRepository.upsert(fromKey, toKey, mode.getDbValue(), payload,
                    LocalDateTime.now().plusDays(ttlDays));
        } catch (Exception e) {
            log.warn("{} 캐시 저장 실패(API 결과는 정상 반환): {}", logLabel, e.getMessage());
        }
    }

    private <T> Optional<T> deserialize(String payload, Class<T> type, String logLabel) {
        try {
            return Optional.of(objectMapper.readValue(payload, type));
        } catch (JsonProcessingException e) {
            log.warn("{} 캐시 역직렬화 실패, API 재호출로 폴백: {}", logLabel, e.getMessage());
            return Optional.empty();
        }
    }
}
