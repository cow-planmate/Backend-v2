package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.route.dto.RoutePointDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OSRM(Open Source Routing Machine)으로 도로를 따라가는 경로를 조회한다.
 * 기본값은 공개 데모 서버이며, api.osrm.base-url 설정으로 자체 서버로 교체할 수 있다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OsrmRouting {

    /** 본 경로 외에 함께 내려줄 대안 경로 최대 개수. */
    private static final int MAX_ALTERNATIVES = 2;

    @Value("${api.osrm.base-url:https://router.project-osrm.org}")
    private String osrmBaseUrl;

    @Value("${api.osrm.foot-base-url:https://routing.openstreetmap.de/routed-foot}")
    private String osrmFootBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 두 지점 이상의 경로를 조회한다. 프로필({@code driving} | {@code foot})에 맞는 서버로 붙고,
     * 턴바이턴 안내(steps)와 대안 경로(alternatives)까지 함께 받아온다 — 셋 다 같은 호출 한 번에
     * 실려오므로 추가 요청 비용이 없다.
     */
    public RoutingResult getRoute(List<RoutePointDto> waypoints, String profile) {
        if (waypoints == null || waypoints.size() < 2) return null;

        String normalized = normalizeProfile(profile);
        String url = UriComponentsBuilder
                .fromUriString(resolveBaseUrl(normalized) + "/route/v1/" + normalized + "/" + buildCoords(waypoints))
                .queryParam("overview", "full")
                .queryParam("geometries", "geojson")
                .queryParam("steps", "true")
                .queryParam("alternatives", "true")
                .toUriString();

        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!"Ok".equals(root.path("code").asText())) {
                log.info("OSRM 경로 없음: {}", root.path("code").asText());
                return null;
            }
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) return null;

            RoutingResult primary = parseRoute(routes.get(0));
            if (primary == null) return null;

            // 첫 경로가 본 경로, 나머지가 대안. 응답/캐시가 과도하게 커지지 않도록 개수를 제한한다.
            List<RouteAlternative> alternatives = new ArrayList<>();
            for (int i = 1; i < routes.size() && alternatives.size() < MAX_ALTERNATIVES; i++) {
                RoutingResult alt = parseRoute(routes.get(i));
                if (alt != null) {
                    alternatives.add(new RouteAlternative(alt.path(), alt.distance(), alt.duration()));
                }
            }

            return new RoutingResult(primary.path(), primary.distance(), primary.duration(),
                    primary.steps(), alternatives);
        } catch (Exception e) {
            log.warn("OSRM 길찾기 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * OSRM {@code routes[i]} 하나를 파싱한다. 좌표가 비면 null을 반환한다.
     * (대안 경로 파싱에도 재사용하므로 alternatives 필드는 비운 채로 돌려준다)
     */
    private RoutingResult parseRoute(JsonNode route) {
        List<RoutePointDto> path = new ArrayList<>();
        for (JsonNode c : route.path("geometry").path("coordinates")) {
            path.add(new RoutePointDto(c.get(1).asDouble(), c.get(0).asDouble(), null));
        }
        if (path.isEmpty()) return null;

        List<RouteStep> steps = new ArrayList<>();
        for (JsonNode leg : route.path("legs")) {
            for (JsonNode step : leg.path("steps")) {
                RouteStep parsed = parseStep(step);
                if (parsed != null) {
                    steps.add(parsed);
                }
            }
        }

        long distance = Math.round(route.path("distance").asDouble(0));
        long duration = Math.round(route.path("duration").asDouble(0));
        return new RoutingResult(path, distance, duration, steps, new ArrayList<>());
    }

    /**
     * OSRM {@code steps[i]} 하나를 파싱한다. 안내 문구는 여기서 만들지 않고
     * maneuver 코드를 그대로 보존한다 — 문구 생성은 서비스 레이어의 책임이다.
     *
     * step 자체의 폴리라인은 담지 않는다. 전체 경로 좌표가 이미 path에 있어 중복이고,
     * 캐시 payload(jsonb)만 배로 불어나기 때문이다. 대신 회전 지점 좌표 하나만 남긴다.
     */
    private RouteStep parseStep(JsonNode step) {
        JsonNode maneuver = step.path("maneuver");
        JsonNode location = maneuver.path("location");

        RoutePointDto point = null;
        if (location.isArray() && location.size() >= 2) {
            point = new RoutePointDto(location.get(1).asDouble(), location.get(0).asDouble(), null);
        }

        String name = step.path("name").asText(null);
        String type = maneuver.path("type").asText(null);
        String modifier = maneuver.path("modifier").asText(null);
        Integer exit = maneuver.has("exit") ? maneuver.path("exit").asInt() : null;

        if (type == null && point == null) {
            return null;
        }
        return new RouteStep(
                Math.round(step.path("distance").asDouble(0)),
                Math.round(step.path("duration").asDouble(0)),
                (name == null || name.isBlank()) ? null : name,
                type,
                (modifier == null || modifier.isBlank()) ? null : modifier,
                exit,
                point);
    }

    /**
     * 좌표 쌍별 소요 시간(초)/거리(m) NxN 매트릭스를 조회한다.
     * 도달 불가 구간은 null 항목으로 유지되며, 실패하면 null을 반환한다.
     */
    public TableResult getTable(List<RoutePointDto> waypoints, String profile) {
        if (waypoints == null || waypoints.size() < 2 || waypoints.stream().anyMatch(Objects::isNull)) {
            return null;
        }

        String normalized = normalizeProfile(profile);
        String url = UriComponentsBuilder
                .fromUriString(resolveBaseUrl(normalized) + "/table/v1/" + normalized + "/" + buildCoords(waypoints))
                .queryParam("annotations", "duration,distance")
                .toUriString();

        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!"Ok".equals(root.path("code").asText())) {
                log.info("OSRM 테이블 조회 실패: {}", root.path("code").asText());
                return null;
            }

            List<List<Double>> durations = parseMatrix(root.path("durations"));
            List<List<Double>> distances = parseMatrix(root.path("distances"));
            if (durations == null || distances == null) {
                return null;
            }
            return new TableResult(durations, distances);
        } catch (Exception e) {
            log.warn("OSRM 테이블 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 방문 순서 최적화(외판원) 경로를 조회한다. 첫 좌표를 출발지로 고정한다.
     * 실패하면 null을 반환한다.
     */
    public TripResult getTrip(List<RoutePointDto> waypoints, String profile, boolean roundtrip) {
        if (waypoints == null || waypoints.size() < 2 || waypoints.stream().anyMatch(Objects::isNull)) {
            return null;
        }

        String normalized = normalizeProfile(profile);
        String url = UriComponentsBuilder
                .fromUriString(resolveBaseUrl(normalized) + "/trip/v1/" + normalized + "/" + buildCoords(waypoints))
                .queryParam("source", "first")
                .queryParam("roundtrip", roundtrip)
                .queryParam("overview", "false")
                .toUriString();

        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!"Ok".equals(root.path("code").asText())) {
                log.info("OSRM 방문 순서 최적화 실패: {}", root.path("code").asText());
                return null;
            }

            JsonNode trips = root.path("trips");
            if (!trips.isArray() || trips.isEmpty()) {
                return null;
            }
            JsonNode trip = trips.get(0);

            // waypoints[i].waypoint_index = 입력 i번 좌표의 방문 순번 → 방문 순서대로 입력 인덱스를 재배열한다.
            JsonNode osrmWaypoints = root.path("waypoints");
            int n = waypoints.size();
            if (!osrmWaypoints.isArray() || osrmWaypoints.size() != n) {
                return null;
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                int position = osrmWaypoints.path(i).path("waypoint_index").asInt(-1);
                if (position < 0 || position >= n || order[position] != null) {
                    return null;
                }
                order[position] = i;
            }

            List<TripLeg> legs = new ArrayList<>();
            for (JsonNode leg : trip.path("legs")) {
                legs.add(new TripLeg(
                        Math.round(leg.path("distance").asDouble(0)),
                        Math.round(leg.path("duration").asDouble(0))));
            }

            long distance = Math.round(trip.path("distance").asDouble(0));
            long duration = Math.round(trip.path("duration").asDouble(0));
            return new TripResult(new ArrayList<>(Arrays.asList(order)), distance, duration, legs);
        } catch (Exception e) {
            log.warn("OSRM 방문 순서 최적화 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 프로필 문자열을 지원 값("driving" | "foot")으로 정규화한다. 알 수 없는 값은 "driving"으로 처리한다.
     */
    public static String normalizeProfile(String profile) {
        if (profile != null && "foot".equalsIgnoreCase(profile.trim())) {
            return "foot";
        }
        return "driving";
    }

    /**
     * 프로필에 맞는 OSRM 서버 주소를 고른다.
     */
    private String resolveBaseUrl(String normalizedProfile) {
        return "foot".equals(normalizedProfile) ? osrmFootBaseUrl : osrmBaseUrl;
    }

    /**
     * OSRM 좌표 문자열(lng,lat;lng,lat;...)을 만든다.
     */
    private String buildCoords(List<RoutePointDto> waypoints) {
        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            RoutePointDto p = waypoints.get(i);
            if (i > 0) coords.append(";");
            coords.append(p.getLng()).append(",").append(p.getLat());
        }
        return coords.toString();
    }

    /**
     * OSRM NxN 매트릭스 응답을 null 항목을 보존하며 파싱한다.
     */
    private List<List<Double>> parseMatrix(JsonNode matrix) {
        if (!matrix.isArray()) {
            return null;
        }
        List<List<Double>> result = new ArrayList<>();
        for (JsonNode row : matrix) {
            List<Double> parsedRow = new ArrayList<>();
            for (JsonNode cell : row) {
                parsedRow.add(cell.isNull() ? null : cell.asDouble());
            }
            result.add(parsedRow);
        }
        return result;
    }

    public record RoutingResult(List<RoutePointDto> path, long distance, long duration,
            List<RouteStep> steps, List<RouteAlternative> alternatives) {}

    /** 턴바이턴 안내 한 단계. {@code maneuverType}/{@code maneuverModifier}는 OSRM 원본 코드다. */
    public record RouteStep(long distance, long duration, String name,
            String maneuverType, String maneuverModifier, Integer exit, RoutePointDto location) {}

    /** 본 경로 대신 선택할 수 있는 대안 경로. */
    public record RouteAlternative(List<RoutePointDto> path, long distance, long duration) {}

    public record TableResult(List<List<Double>> durations, List<List<Double>> distances) {}

    public record TripResult(List<Integer> visitOrder, long totalDistance, long totalDuration, List<TripLeg> legs) {}

    public record TripLeg(long distance, long duration) {}
}
