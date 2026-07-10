package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.route.dto.RoutePointDto;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
@RequiredArgsConstructor
public class OsrmRouting {

    @Value("${api.osrm.base-url:https://router.project-osrm.org}")
    private String osrmBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RoutingResult getRoute(List<RoutePointDto> waypoints) {
        if (waypoints == null || waypoints.size() < 2) return null;

        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            RoutePointDto p = waypoints.get(i);
            if (i > 0) coords.append(";");
            coords.append(p.getLng()).append(",").append(p.getLat());
        }

        String url = UriComponentsBuilder.fromUriString(osrmBaseUrl + "/route/v1/driving/" + coords)
                .queryParam("overview", "full")
                .queryParam("geometries", "geojson")
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
            JsonNode route = routes.get(0);
            JsonNode coordinates = route.path("geometry").path("coordinates");
            List<RoutePointDto> path = new ArrayList<>();
            for (JsonNode c : coordinates) {
                double lng = c.get(0).asDouble();
                double lat = c.get(1).asDouble();
                path.add(new RoutePointDto(lat, lng));
            }
            if (path.isEmpty()) return null;
            long distance = Math.round(route.path("distance").asDouble(0));
            long duration = Math.round(route.path("duration").asDouble(0));
            return new RoutingResult(path, distance, duration);
        } catch (Exception e) {
            log.warn("OSRM 길찾기 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    public record RoutingResult(List<RoutePointDto> path, long distance, long duration) {}
}
