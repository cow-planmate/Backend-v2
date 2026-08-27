package com.planmate.domain.route.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.route.dto.RoutePointDto;
import com.planmate.domain.route.dto.RouteRequest;
import com.planmate.domain.route.dto.RouteTableRequest;
import com.planmate.domain.route.dto.RouteTripRequest;
import com.planmate.domain.route.dto.TransitLaneRequest;
import com.planmate.domain.route.dto.TransitRouteRequest;
import com.planmate.domain.route.entity.RouteCache;
import com.planmate.domain.route.enums.RouteCacheMode;
import com.planmate.domain.route.repository.RouteCacheRepository;
import com.planmate.domain.route.repository.RouteTransitLaneCacheRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 길찾기 캐싱 그룹 통합 테스트. WeatherControllerTest/OAuthControllerTest와 동일한 패턴을 따른다 —
 * 실제 로컬 DB(route_cache/route_transit_lane_cache 포함)와 실제 Spring 빈 배선은 그대로 쓰고,
 * 외부 HTTP 경계(RestTemplate)만 목킹해 OSRM/ODsay의 실제 응답 스키마를 그대로 재현한다.
 * 진짜 외부망을 타는 1회성 라이브 검증은 별도로 수행하고 여기엔 포함하지 않는다(비용/플레이키니스).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "api.odsay.key=test-dummy-key")
class RouteControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private RouteCacheRepository routeCacheRepository;
    @Autowired
    private RouteTransitLaneCacheRepository laneCacheRepository;
    @Autowired
    private com.planmate.domain.route.service.RouteCachePruner routeCachePruner;

    @MockitoBean
    private RestTemplate restTemplate;

    private String accessToken;

    private static final String OSRM_ROUTE_JSON =
            "{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":"
                    + "[[127.0,37.5],[127.05,37.55],[127.1,37.6]]},\"distance\":1200,\"duration\":222.0}]}";

    private static final String OSRM_ROUTE_WITH_STEPS_JSON =
            "{\"code\":\"Ok\",\"routes\":["
                    + "{\"geometry\":{\"coordinates\":[[127.0,37.5],[127.1,37.6]]},"
                    + "\"distance\":1200,\"duration\":222.0,\"legs\":[{\"steps\":["
                    + "{\"distance\":300.0,\"duration\":60.0,\"name\":\"세종대로\","
                    + "\"maneuver\":{\"type\":\"depart\",\"location\":[127.0,37.5]}},"
                    + "{\"distance\":900.0,\"duration\":162.0,\"name\":\"을지로\","
                    + "\"maneuver\":{\"type\":\"turn\",\"modifier\":\"right\",\"location\":[127.05,37.55]}}"
                    + "]}]},"
                    + "{\"geometry\":{\"coordinates\":[[127.0,37.5],[127.2,37.7]]},"
                    + "\"distance\":1500,\"duration\":300.0,\"legs\":[]}]}";

    private static final String OSRM_FOOT_ROUTE_JSON =
            "{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":[[127.0,37.5],[127.1,37.6]]},"
                    + "\"distance\":2000,\"duration\":2400.0,\"legs\":[{\"steps\":["
                    + "{\"distance\":2000.0,\"duration\":2400.0,\"name\":\"\","
                    + "\"maneuver\":{\"type\":\"depart\",\"location\":[127.0,37.5]}}]}]}]}";

    private static final String OSRM_TRIP_JSON =
            "{\"code\":\"Ok\","
                    + "\"waypoints\":[{\"waypoint_index\":0},{\"waypoint_index\":1},{\"waypoint_index\":2}],"
                    + "\"trips\":[{\"distance\":3000.0,\"duration\":400.0,"
                    + "\"legs\":[{\"distance\":1000.0,\"duration\":100.0},{\"distance\":2000.0,\"duration\":300.0}]}]}";

    private static final String ODSAY_TRANSIT_JSON =
            "{\"result\":{\"busCount\":0,\"subwayCount\":1,\"subwayBusCount\":0,\"path\":["
                    + "{\"pathType\":1,\"info\":{\"totalTime\":30,\"payment\":1500,\"totalWalk\":300,"
                    + "\"totalDistance\":5000,\"busTransitCount\":0,\"subwayTransitCount\":1,"
                    + "\"firstStartStation\":\"홍대입구\",\"lastEndStation\":\"강남\","
                    + "\"mapObj\":\"19:2:1609:1606@6:2:627:631\"},\"subPath\":[]}]}}";

    private static final String ODSAY_LANE_JSON =
            "{\"result\":{\"lane\":[{\"class\":2,\"type\":202,\"section\":["
                    + "{\"graphPos\":[{\"x\":127.0,\"y\":37.5},{\"x\":127.05,\"y\":37.55}]}]}]}}";

    @BeforeEach
    void setUp() {
        User testUser = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("route-test-" + UUID.randomUUID() + "@example.com")
                .nickname("route-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        accessToken = jwtTokenProvider.createAccessToken(testUser.getUserId(), testUser.getRole().name());
    }

    private RoutePointDto point(double lat, double lng, String placeId) {
        return new RoutePointDto(lat, lng, placeId);
    }

    // ---------- 인증 실패 대표 케이스 (그룹 공통) ----------

    @Test
    @DisplayName("토큰 없이 호출하면 401을 반환한다")
    void noToken_returns401() throws Exception {
        RouteRequest request = new RouteRequest(List.of(point(37.5, 127.0, "A"), point(37.6, 127.1, "B")), "driving");

        mockMvc.perform(post("/api/route/directions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- /api/route/directions ----------

    @Test
    @DisplayName("directions - 캐시 미스 시 OSRM을 호출해 응답하고 DB에 캐시가 남으며, 재호출 시 캐시를 재사용한다")
    void getDirections_cacheMissThenHit() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "P_HONGDAE");
        RoutePointDto b = point(37.6, 127.1, "P_GANGNAM");
        RouteRequest request = new RouteRequest(List.of(a, b), "driving");

        given(restTemplate.getForObject(argThat((String url) -> url != null && url.contains("/route/v1/driving/")), eq(String.class)))
                .willReturn(OSRM_ROUTE_JSON);

        // 1차 호출 - 캐시 미스
        mockMvc.perform(post("/api/route/directions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distance").value(1200))
                .andExpect(jsonPath("$.duration").value(222.0))
                .andExpect(jsonPath("$.path.length()").value(3));

        Optional<RouteCache> cached = routeCacheRepository
                .findByFromKeyAndToKeyAndMode("P:P_HONGDAE", "P:P_GANGNAM", RouteCacheMode.DRIVING);
        assertThat(cached).isPresent();

        // 2차 호출 - 캐시 히트, OSRM은 딱 1번만 불렸어야 함(1차 호출분)
        mockMvc.perform(post("/api/route/directions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distance").value(1200));

        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class));
    }

    // ---------- /api/route/table ----------

    @Test
    @DisplayName("table - directions와 같은 인접 쌍이면 캐시 행을 공유해 OSRM을 다시 호출하지 않는다")
    void getTable_sharesCacheWithDirections() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "P_A");
        RoutePointDto b = point(37.6, 127.1, "P_B");

        given(restTemplate.getForObject(argThat((String url) -> url != null && url.contains("/route/v1/driving/")), eq(String.class)))
                .willReturn(OSRM_ROUTE_JSON);

        // directions 먼저 호출해서 (P_A,P_B,driving) 캐시를 만든다
        mockMvc.perform(post("/api/route/directions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RouteRequest(List.of(a, b), "driving"))))
                .andExpect(status().isOk());

        // table을 같은 쌍으로 호출 - 같은 캐시 행을 읽어야 하므로 OSRM 추가 호출이 없어야 한다
        mockMvc.perform(post("/api/route/table")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RouteTableRequest(List.of(a, b), "driving"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distances[0][1]").value(1200))
                .andExpect(jsonPath("$.durations[0][1]").value(222.0))
                .andExpect(jsonPath("$.distances[0][0]").doesNotExist())
                .andExpect(jsonPath("$.profile").value("driving"));

        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class));
    }

    @Test
    @DisplayName("directions - OSRM steps/alternatives를 한국어 안내가 채워진 구간 정보로 반환한다")
    void getDirections_returnsStepsAndAlternatives() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "P_STEP_A");
        RoutePointDto b = point(37.6, 127.1, "P_STEP_B");

        given(restTemplate.getForObject(
                argThat((String url) -> url != null && url.contains("steps=true") && url.contains("alternatives=true")),
                eq(String.class)))
                .willReturn(OSRM_ROUTE_WITH_STEPS_JSON);

        mockMvc.perform(post("/api/route/directions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RouteRequest(List.of(a, b), "driving"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("driving"))
                .andExpect(jsonPath("$.legs[0].fromIndex").value(0))
                .andExpect(jsonPath("$.legs[0].toIndex").value(1))
                .andExpect(jsonPath("$.legs[0].steps.length()").value(2))
                .andExpect(jsonPath("$.legs[0].steps[0].instruction").value("세종대로에서 출발"))
                .andExpect(jsonPath("$.legs[0].steps[1].instruction").value("을지로 방면으로 우회전"))
                .andExpect(jsonPath("$.legs[0].steps[1].maneuverType").value("turn"))
                .andExpect(jsonPath("$.legs[0].alternatives.length()").value(1))
                .andExpect(jsonPath("$.legs[0].alternatives[0].duration").value(300));
    }

    @Test
    @DisplayName("table - foot 프로필이면 driving이 아니라 도보 서버/프로필로 조회한다")
    void getTable_footProfile_callsFootRouting() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "P_FOOT_A");
        RoutePointDto b = point(37.6, 127.1, "P_FOOT_B");

        given(restTemplate.getForObject(
                argThat((String url) -> url != null && url.contains("/route/v1/foot/")), eq(String.class)))
                .willReturn(OSRM_FOOT_ROUTE_JSON);

        mockMvc.perform(post("/api/route/table")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RouteTableRequest(List.of(a, b), "foot"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("foot"))
                .andExpect(jsonPath("$.durations[0][1]").value(2400.0))
                .andExpect(jsonPath("$.legs[0].steps.length()").value(1));

        assertThat(routeCacheRepository
                .findByFromKeyAndToKeyAndMode("P:P_FOOT_A", "P:P_FOOT_B", RouteCacheMode.FOOT))
                .isPresent();
        verify(restTemplate).getForObject(
                argThat((String url) -> url != null && url.contains("routed-foot") && url.contains("/route/v1/foot/")),
                eq(String.class));
    }

    // ---------- /api/route/trip (캐싱 대상 아님, smoke만) ----------

    @Test
    @DisplayName("trip - 캐싱 대상이 아니며 OSRM 응답을 그대로 방문 순서로 변환해 반환한다")
    void getTrip_success_notCached() throws Exception {
        RoutePointDto a = point(37.5, 127.0, "P_A");
        RoutePointDto b = point(37.6, 127.1, "P_B");
        RoutePointDto c = point(37.7, 127.2, "P_C");

        given(restTemplate.getForObject(argThat((String url) -> url != null && url.contains("/trip/v1/driving/")), eq(String.class)))
                .willReturn(OSRM_TRIP_JSON);

        mockMvc.perform(post("/api/route/trip")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RouteTripRequest(List.of(a, b, c), "driving", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitOrder.length()").value(3))
                .andExpect(jsonPath("$.totalDistance").value(3000))
                .andExpect(jsonPath("$.totalDuration").value(400));
    }

    // ---------- /api/route/transit ----------

    @Test
    @DisplayName("transit - 캐시 미스 시 ODsay를 호출해 응답하고 DB에 캐시가 남으며, 재호출 시 캐시를 재사용한다")
    void getTransit_cacheMissThenHit() throws Exception {
        RoutePointDto from = point(37.5, 127.0, "P_FROM");
        RoutePointDto to = point(37.6, 127.1, "P_TO");
        TransitRouteRequest request = new TransitRouteRequest(from, to);

        given(restTemplate.getForObject(
                        argThat((URI uri) -> uri != null && uri.toString().contains("searchPubTransPathT")),
                        eq(String.class)))
                .willReturn(ODSAY_TRANSIT_JSON);

        mockMvc.perform(post("/api/route/transit")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].mapObj").value("19:2:1609:1606@6:2:627:631"));

        Optional<RouteCache> cached = routeCacheRepository
                .findByFromKeyAndToKeyAndMode("P:P_FROM", "P:P_TO", RouteCacheMode.TRANSIT);
        assertThat(cached).isPresent();

        mockMvc.perform(post("/api/route/transit")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));

        verify(restTemplate, times(1)).getForObject(any(URI.class), eq(String.class));
    }

    // ---------- /api/route/transit/lane ----------

    @Test
    @DisplayName("transit/lane - 캐시 미스 시 ODsay loadLane을 호출해 응답하고, 재호출 시 캐시를 재사용한다")
    void getTransitLane_cacheMissThenHit() throws Exception {
        String mapObj = "19:2:1609:1606@6:2:627:631";
        TransitLaneRequest request = new TransitLaneRequest(mapObj);

        given(restTemplate.getForObject(
                        argThat((URI uri) -> uri != null && uri.toString().contains("loadLane")),
                        eq(String.class)))
                .willReturn(ODSAY_LANE_JSON);

        mockMvc.perform(post("/api/route/transit/lane")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lanes.length()").value(1))
                .andExpect(jsonPath("$.lanes[0].trafficClass").value(2))
                .andExpect(jsonPath("$.lanes[0].path.length()").value(2));

        assertThat(laneCacheRepository.findByMapObj(mapObj)).isPresent();

        mockMvc.perform(post("/api/route/transit/lane")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lanes.length()").value(1));

        verify(restTemplate, times(1)).getForObject(any(URI.class), eq(String.class));
    }

    // ---------- placeId 없는 좌표 폴백 ----------

    @Test
    @DisplayName("directions - placeId 없이 좌표만 보내도 정상 동작하고 반올림 좌표 키로 캐시된다")
    void getDirections_noPlaceId_coordinateFallbackCaches() throws Exception {
        RoutePointDto a = point(37.123456, 127.123456, null);
        RoutePointDto b = point(37.654321, 127.654321, null);

        given(restTemplate.getForObject(argThat((String url) -> url != null && url.contains("/route/v1/driving/")), eq(String.class)))
                .willReturn(OSRM_ROUTE_JSON);

        mockMvc.perform(post("/api/route/directions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RouteRequest(List.of(a, b), "driving"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distance").value(1200));

        Optional<RouteCache> cached = routeCacheRepository.findByFromKeyAndToKeyAndMode(
                "C:37.12346,127.12346", "C:37.65432,127.65432", RouteCacheMode.DRIVING);
        assertThat(cached).isPresent();
    }

    // ---------- 네이티브 upsert(ON CONFLICT) 실제 DB 동작 ----------

    @Test
    @DisplayName("route_cache upsert - 같은 (from,to,mode) 키로 두 번 upsert해도 유니크 제약 위반 없이 한 행만 남고 최신값으로 갱신된다")
    void routeCacheUpsert_sameKeyTwice_updatesInPlaceWithoutConflict() {
        String fromKey = "P:UPSERT_A";
        String toKey = "P:UPSERT_B";

        routeCacheRepository.upsert(fromKey, toKey, "driving", "{\"v\":1}", java.time.LocalDateTime.now().plusDays(1));
        // 같은 키로 두 번째 upsert — 여기서 unique 제약(uk_route_cache_key) 위반 예외가 나지 않아야 한다
        routeCacheRepository.upsert(fromKey, toKey, "driving", "{\"v\":2}", java.time.LocalDateTime.now().plusDays(2));

        Optional<RouteCache> row = routeCacheRepository.findByFromKeyAndToKeyAndMode(fromKey, toKey, RouteCacheMode.DRIVING);
        assertThat(row).isPresent();
        // jsonb는 저장 시 텍스트를 정규화한다(예: 콜론 뒤 공백 추가) — 공백 제거 후 내용만 비교한다.
        assertThat(row.get().getResponsePayload().replaceAll("\\s+", "")).isEqualTo("{\"v\":2}"); // 최신값으로 덮어써졌는지 확인
    }

    @Test
    @DisplayName("route_transit_lane_cache upsert - 같은 mapObj로 두 번 upsert해도 한 행만 남고 최신값으로 갱신된다")
    void laneCacheUpsert_sameMapObjTwice_updatesInPlaceWithoutConflict() {
        String mapObj = "UPSERT_MAPOBJ";

        laneCacheRepository.upsert(mapObj, "{\"v\":1}", java.time.LocalDateTime.now().plusDays(1));
        laneCacheRepository.upsert(mapObj, "{\"v\":2}", java.time.LocalDateTime.now().plusDays(2));

        Optional<com.planmate.domain.route.entity.RouteTransitLaneCache> row = laneCacheRepository.findByMapObj(mapObj);
        assertThat(row).isPresent();
        assertThat(row.get().getResponsePayload().replaceAll("\\s+", "")).isEqualTo("{\"v\":2}");
    }

    // ---------- RouteCachePruner 실제 DB 동작 ----------

    @Test
    @DisplayName("pruner - 만료된 route_cache/lane_cache 행만 지우고 유효한 행은 남긴다")
    void pruner_deletesOnlyExpiredRows_realDb() {
        java.time.LocalDateTime past = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime future = java.time.LocalDateTime.now().plusDays(30);

        routeCacheRepository.upsert("P:EXPIRED_A", "P:EXPIRED_B", "driving", "{}", past);
        routeCacheRepository.upsert("P:VALID_A", "P:VALID_B", "driving", "{}", future);
        laneCacheRepository.upsert("EXPIRED_MAPOBJ", "{}", past);
        laneCacheRepository.upsert("VALID_MAPOBJ", "{}", future);

        routeCachePruner.prune();

        assertThat(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:EXPIRED_A", "P:EXPIRED_B", RouteCacheMode.DRIVING))
                .isEmpty();
        assertThat(routeCacheRepository.findByFromKeyAndToKeyAndMode("P:VALID_A", "P:VALID_B", RouteCacheMode.DRIVING))
                .isPresent();
        assertThat(laneCacheRepository.findByMapObj("EXPIRED_MAPOBJ")).isEmpty();
        assertThat(laneCacheRepository.findByMapObj("VALID_MAPOBJ")).isPresent();
    }
}
