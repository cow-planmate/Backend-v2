package com.planmate.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.externalAPI.OdsayTransit;
import com.planmate.common.externalAPI.OdsayTransit.TransitLaneResult;
import com.planmate.common.externalAPI.OdsayTransit.TransitResult;
import com.planmate.common.externalAPI.OsrmRouting;
import com.planmate.common.externalAPI.OsrmRouting.RoutingResult;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.RouteResponse;
import com.planmate.domain.route.dto.RouteTableResponse;
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
class RouteServiceTest {

    @Mock
    private OsrmRouting osrmRouting;
    @Mock
    private OdsayTransit odsayTransit;
    @Mock
    private RouteCacheRepository routeCacheRepository;
    @Mock
    private RouteTransitLaneCacheRepository laneCacheRepository;

    // ObjectMapper는 실제 JSON 라운드트립(record 직렬화 포함)을 검증해야 하므로 목킹하지 않는다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(osrmRouting, odsayTransit, routeCacheRepository, laneCacheRepository, objectMapper);
    }

    private RoutePointDto point(double lat, double lng, String placeId) {
        return new RoutePointDto(lat, lng, placeId);
    }

    private RouteCache cacheRow(String payload) {
        return RouteCache.builder()
                .routeCacheId(1L)
                .fromKey("P:A")
                .toKey("P:B")
                .mode(RouteCacheMode.DRIVING)
                .responsePayload(payload)
                .expiredAt(LocalDateTime.now().plusDays(1))
                .build();
    }

    @Test
    @DisplayName("getRoute - 캐시 미존재 시 OSRM을 호출하고 결과를 캐시에 저장한다")
    void getRoute_cacheMiss_callsOsrmAndCaches() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "A");
        RoutePointDto b = point(37.6, 127.1, "B");
        RoutingResult leg = new RoutingResult(List.of(a, b), 1000L, 120L);

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.DRIVING))
                .willReturn(Optional.empty());
        given(osrmRouting.getRoute(List.of(a, b))).willReturn(leg);

        RouteResponse response = routeService.getRoute(List.of(a, b));

        assertThat(response.getDistance()).isEqualTo(1000L);
        assertThat(response.getDuration()).isEqualTo(120L);
        assertThat(response.getPath()).hasSize(2);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> expiredAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(routeCacheRepository).upsert(eq("P:A"), eq("P:B"), eq("driving"), payloadCaptor.capture(), expiredAtCaptor.capture());
        RoutingResult savedPayload = objectMapper.readValue(payloadCaptor.getValue(), RoutingResult.class);
        assertThat(savedPayload.distance()).isEqualTo(1000L);
        // driving/foot TTL은 30일이어야 한다(§4) — 정확히 30일인지 근사(±1분)로 확인
        assertThat(expiredAtCaptor.getValue())
                .isCloseTo(LocalDateTime.now().plusDays(30), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("getRoute - 캐시 히트 시 OSRM을 호출하지 않고 캐시 값을 그대로 반환한다")
    void getRoute_cacheHit_doesNotCallOsrm() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "A");
        RoutePointDto b = point(37.6, 127.1, "B");
        RoutingResult cached = new RoutingResult(List.of(a, b), 500L, 60L);
        String payload = objectMapper.writeValueAsString(cached);

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.DRIVING))
                .willReturn(Optional.of(cacheRow(payload)));

        RouteResponse response = routeService.getRoute(List.of(a, b));

        assertThat(response.getDistance()).isEqualTo(500L);
        assertThat(response.getDuration()).isEqualTo(60L);
        verify(osrmRouting, never()).getRoute(any());
    }

    @Test
    @DisplayName("getRoute - 만료된 캐시는 무시하고 OSRM을 다시 호출한다")
    void getRoute_expiredCache_callsOsrmAgain() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "A");
        RoutePointDto b = point(37.6, 127.1, "B");
        RoutingResult stale = new RoutingResult(List.of(a, b), 999L, 999L);
        RouteCache expired = RouteCache.builder()
                .routeCacheId(1L)
                .fromKey("P:A")
                .toKey("P:B")
                .mode(RouteCacheMode.DRIVING)
                .responsePayload(objectMapper.writeValueAsString(stale))
                .expiredAt(LocalDateTime.now().minusDays(1)) // 이미 만료됨
                .build();
        RoutingResult fresh = new RoutingResult(List.of(a, b), 1000L, 120L);

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.DRIVING))
                .willReturn(Optional.of(expired));
        given(osrmRouting.getRoute(List.of(a, b))).willReturn(fresh);

        RouteResponse response = routeService.getRoute(List.of(a, b));

        assertThat(response.getDistance()).isEqualTo(1000L);
        verify(osrmRouting).getRoute(List.of(a, b));
    }

    @Test
    @DisplayName("getRoute - 좌표만 있고 placeId가 없으면 반올림한 좌표를 캐시 키로 쓴다")
    void getRoute_noPlaceId_usesRoundedCoordinateKey() {
        RoutePointDto a = point(37.123456, 127.123456, null);
        RoutePointDto b = point(37.654321, 127.654321, null);
        given(routeCacheRepository.findByFromKeyAndToKeyAndMode(anyString(), anyString(), eq(RouteCacheMode.DRIVING)))
                .willReturn(Optional.empty());
        given(osrmRouting.getRoute(List.of(a, b)))
                .willReturn(new RoutingResult(List.of(a, b), 100L, 10L));

        routeService.getRoute(List.of(a, b));

        verify(routeCacheRepository).findByFromKeyAndToKeyAndMode(
                "C:37.12346,127.12346", "C:37.65432,127.65432", RouteCacheMode.DRIVING);
    }

    @Test
    @DisplayName("getRoute - 어느 한 구간이라도 OSRM 실패면 입력 좌표를 그대로 반환한다(기존 폴백 유지)")
    void getRoute_anyLegFails_fallsBackToStraightLine() {
        RoutePointDto a = point(37.5, 127.0, "A");
        RoutePointDto b = point(37.6, 127.1, "B");
        given(routeCacheRepository.findByFromKeyAndToKeyAndMode(anyString(), anyString(), any()))
                .willReturn(Optional.empty());
        given(osrmRouting.getRoute(List.of(a, b))).willReturn(null);

        RouteResponse response = routeService.getRoute(List.of(a, b));

        assertThat(response.getDistance()).isZero();
        assertThat(response.getDuration()).isZero();
        assertThat(response.getPath()).containsExactly(a, b);
    }

    @Test
    @DisplayName("getRoute - 3점 이상이면 인접 쌍 경로를 경계점 중복 없이 이어 붙인다")
    void getRoute_multiplePairs_stitchesPathsWithoutDuplicateBoundary() {
        RoutePointDto a = point(37.0, 127.0, "A");
        RoutePointDto b = point(37.1, 127.1, "B");
        RoutePointDto c = point(37.2, 127.2, "C");
        RoutePointDto mid1 = point(37.05, 127.05, null);
        RoutePointDto mid2 = point(37.15, 127.15, null);

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode(anyString(), anyString(), any()))
                .willReturn(Optional.empty());
        given(osrmRouting.getRoute(List.of(a, b)))
                .willReturn(new RoutingResult(List.of(a, mid1, b), 100L, 10L));
        given(osrmRouting.getRoute(List.of(b, c)))
                .willReturn(new RoutingResult(List.of(b, mid2, c), 200L, 20L));

        RouteResponse response = routeService.getRoute(List.of(a, b, c));

        assertThat(response.getPath()).containsExactly(a, mid1, b, mid2, c); // b(경계점) 중복 없음
        assertThat(response.getDistance()).isEqualTo(300L);
        assertThat(response.getDuration()).isEqualTo(30L);
    }

    @Test
    @DisplayName("getTable - 인접 칸만 채우고 나머지는 null인 매트릭스를 반환한다")
    void getTable_fillsOnlyAdjacentCells() {
        RoutePointDto a = point(37.0, 127.0, "A");
        RoutePointDto b = point(37.1, 127.1, "B");
        RoutePointDto c = point(37.2, 127.2, "C");

        given(routeCacheRepository.findByFromKeyAndToKeyAndMode(anyString(), anyString(), eq(RouteCacheMode.DRIVING)))
                .willReturn(Optional.empty());
        given(osrmRouting.getRoute(List.of(a, b))).willReturn(new RoutingResult(List.of(a, b), 100L, 10L));
        given(osrmRouting.getRoute(List.of(b, c))).willReturn(new RoutingResult(List.of(b, c), 200L, 20L));

        RouteTableResponse response = routeService.getTable(List.of(a, b, c), "driving");

        assertThat(response.getDistances().get(0).get(1)).isEqualTo(100.0);
        assertThat(response.getDurations().get(0).get(1)).isEqualTo(10.0);
        assertThat(response.getDistances().get(1).get(2)).isEqualTo(200.0);
        assertThat(response.getDistances().get(0).get(2)).isNull(); // 인접하지 않은 칸은 null
        assertThat(response.getDistances().get(0).get(0)).isNull();
        assertThat(response.getProfile()).isEqualTo("driving");
    }

    @Test
    @DisplayName("getTable/getRoute - 같은 인접 쌍이면 캐시 행을 공유한다(table 미스 시에도 OSRM /route 호출)")
    void tableAndDirections_shareSameCacheEntry() {
        RoutePointDto a = point(37.0, 127.0, "A");
        RoutePointDto b = point(37.1, 127.1, "B");
        given(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:A", "P:B", RouteCacheMode.DRIVING))
                .willReturn(Optional.empty());
        given(osrmRouting.getRoute(List.of(a, b))).willReturn(new RoutingResult(List.of(a, b), 100L, 10L));

        routeService.getTable(List.of(a, b), "driving");

        // table 미스 시에도 /table이 아니라 /route를 호출해 path까지 채운 캐시를 남긴다
        verify(osrmRouting).getRoute(List.of(a, b));
        verify(routeCacheRepository).upsert(eq("P:A"), eq("P:B"), eq("driving"), anyString(), any());
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

        TransitRouteResponse response = routeService.getTransit(from, to);

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

        TransitRouteResponse response = routeService.getTransit(from, to);

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

        TransitRouteResponse response = routeService.getTransit(from, to);

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

        TransitLaneResponse response = routeService.getTransitLane("mapObj123");

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

        TransitLaneResponse response = routeService.getTransitLane("mapObj123");

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
        TransitLaneResponse response = routeService.getTransitLane("");

        assertThat(response.getLanes()).isEmpty();
        verify(laneCacheRepository, never()).findByMapObj(anyString());
        verify(odsayTransit, never()).getLane(anyString());
    }
}
