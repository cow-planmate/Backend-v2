package com.planmate.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.externalAPI.OdsayTransit;
import com.planmate.common.externalAPI.OdsayTransit.TransitLaneResult;
import com.planmate.common.externalAPI.OdsayTransit.TransitResult;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.TransitLaneDto;
import com.planmate.domain.route.dto.TransitLaneResponse;
import com.planmate.domain.route.dto.TransitRouteOptionDto;
import com.planmate.domain.route.dto.TransitRouteResponse;
import com.planmate.domain.route.entity.RouteCache;
import com.planmate.domain.route.entity.RouteTransitLaneCache;
import com.planmate.domain.route.enums.RouteCacheMode;
import com.planmate.domain.route.repository.RouteCacheRepository;
import com.planmate.domain.route.repository.RouteTransitLaneCacheRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransitRouteServiceTest {

    @Mock
    private OdsayTransit odsayTransit;
    @Mock
    private RouteCacheRepository routeCacheRepository;
    @Mock
    private RouteTransitLaneCacheRepository laneCacheRepository;

    // ObjectMapper는 실제 JSON 라운드트립(record 직렬화 포함)을 검증해야 하므로 목킹하지 않는다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TransitRouteService transitRouteService;

    @BeforeEach
    void setUp() {
        RouteCacheSupport routeCacheSupport = new RouteCacheSupport(routeCacheRepository, laneCacheRepository, objectMapper);
        transitRouteService = new TransitRouteService(
                odsayTransit, routeCacheRepository, laneCacheRepository, objectMapper, routeCacheSupport);
    }

    private RoutePointDto point(double lat, double lng, String placeId) {
        return new RoutePointDto(lat, lng, placeId);
    }

    @Test
    @DisplayName("getTransit - 캐시 히트 시 ODsay를 호출하지 않는다")
    void getTransit_cacheHit_doesNotCallOdsay() throws Exception {
        RoutePointDto from = point(37.5, 127.0, "A");
        RoutePointDto to = point(37.6, 127.1, "B");
        TransitRouteResponse cached = new TransitRouteResponse(true, null,
                List.of(new TransitRouteOptionDto(1, 30, 1500, 500, 5000, 0, 1,
                        "출발역", "도착역", List.of(), "mapObj123")),
                0, 1, 0);
        String payload = objectMapper.writeValueAsString(cached);
        RouteCache row = RouteCache.builder()
                .routeCacheId(1L).fromKey("P:A").toKey("P:B").mode(RouteCacheMode.TRANSIT)
                .responsePayload(payload).expiredAt(LocalDateTime.now().plusDays(1)).build();

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.TRANSIT))
                .willReturn(Optional.of(row));

        TransitRouteResponse response = transitRouteService.getTransit(from, to);

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getRoutes()).hasSize(1);
        verify(odsayTransit, never()).getTransitPath(any(), any());
    }

    @Test
    @DisplayName("getTransit - 캐시 미스 + 조회 성공 시 결과를 캐시에 저장한다")
    void getTransit_cacheMiss_available_caches() {
        RoutePointDto from = point(37.5, 127.0, "A");
        RoutePointDto to = point(37.6, 127.1, "B");
        TransitResult result = new TransitResult(true, null, List.of(), 0, 1, 0);

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.TRANSIT))
                .willReturn(Optional.empty());
        given(odsayTransit.getTransitPath(from, to)).willReturn(result);

        TransitRouteResponse response = transitRouteService.getTransit(from, to);

        assertThat(response.isAvailable()).isTrue();
        ArgumentCaptor<LocalDateTime> expiredAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(routeCacheRepository).upsert(eq("P:A"), eq("P:B"), eq("transit"), anyString(), expiredAtCaptor.capture());
        // transit TTL은 14일이어야 한다(§4)
        assertThat(expiredAtCaptor.getValue())
                .isCloseTo(LocalDateTime.now().plusDays(14), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("getTransit - 조회 불가(unavailable) 결과는 캐시하지 않는다")
    void getTransit_unavailable_doesNotCache() {
        RoutePointDto from = point(37.5, 127.0, "A");
        RoutePointDto to = point(37.6, 127.1, "B");
        TransitResult result = TransitResult.unavailable("경로 없음");

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.TRANSIT))
                .willReturn(Optional.empty());
        given(odsayTransit.getTransitPath(from, to)).willReturn(result);

        TransitRouteResponse response = transitRouteService.getTransit(from, to);

        assertThat(response.isAvailable()).isFalse();
        verify(routeCacheRepository, never()).upsert(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("getTransitLane - 캐시 히트 시 ODsay를 호출하지 않는다")
    void getTransitLane_cacheHit_doesNotCallOdsay() throws Exception {
        TransitLaneResponse cached = new TransitLaneResponse(
                List.of(new TransitLaneDto(1, 11, List.of(new RoutePointDto(37.5, 127.0, null)))));
        String payload = objectMapper.writeValueAsString(cached);
        RouteTransitLaneCache row = RouteTransitLaneCache.builder()
                .laneCacheId(1L).mapObj("mapObj123").responsePayload(payload)
                .expiredAt(LocalDateTime.now().plusDays(1)).build();

        given(laneCacheRepository.findByMapObj("mapObj123")).willReturn(Optional.of(row));

        TransitLaneResponse response = transitRouteService.getTransitLane("mapObj123");

        assertThat(response.getLanes()).hasSize(1);
        verify(odsayTransit, never()).getLane(anyString());
    }

    @Test
    @DisplayName("getTransitLane - 캐시 미스 + 노선 존재 시 캐시에 저장한다")
    void getTransitLane_cacheMiss_withLanes_caches() {
        TransitLaneResult result = new TransitLaneResult(
                List.of(new TransitLaneDto(1, 11, List.of(new RoutePointDto(37.5, 127.0, null)))));

        given(laneCacheRepository.findByMapObj("mapObj123")).willReturn(Optional.empty());
        given(odsayTransit.getLane("mapObj123")).willReturn(result);

        TransitLaneResponse response = transitRouteService.getTransitLane("mapObj123");

        assertThat(response.getLanes()).hasSize(1);
        ArgumentCaptor<LocalDateTime> expiredAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(laneCacheRepository).upsert(eq("mapObj123"), anyString(), expiredAtCaptor.capture());
        // lane TTL은 transit과 동일하게 14일이어야 한다(§4)
        assertThat(expiredAtCaptor.getValue())
                .isCloseTo(LocalDateTime.now().plusDays(14), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("getTransitLane - mapObj가 비어있으면 빈 응답을 반환하고 아무것도 호출하지 않는다")
    void getTransitLane_blankMapObj_returnsEmpty() {
        TransitLaneResponse response = transitRouteService.getTransitLane("");

        assertThat(response.getLanes()).isEmpty();
        verify(laneCacheRepository, never()).findByMapObj(anyString());
        verify(odsayTransit, never()).getLane(anyString());
    }
}
