package com.planmate.common.externalAPI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.exception.domain.GooglePlacesApiCallFailedException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Google Places Text Search 클라이언트 — 키워드 검색 전용(추천 경로는 TourApiClient가 담당).
 * project_ai/04_기능/place/06_키워드검색_API_인프라설계.md 3장.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GoogleSearchClient {

    private static final String TEXT_SEARCH_URL = "https://maps.googleapis.com/maps/api/place/textsearch/json";
    // v1 계승 — 06번 문서 §3-2. 제한이 아니라 소프트 가중치(반경 밖도 나올 수 있음).
    private static final int BIAS_RADIUS_METERS = 50000;

    @Qualifier("googlePlacesRestTemplate")
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${api.google.key:}")
    private String googleApiKey;

    /** query(필수) + lat/lng(선택, destinationId 기반 좌표 바이어스) 로 Text Search를 호출한다. */
    public GoogleTextSearchResult searchPlace(String query, Double lat, Double lng) {
        ensureKeyConfigured();
        String url = buildTextSearchUrl(query, lat, lng);
        return parseTextSearch(callHttpWithRetry(url));
    }

    /** 직전 응답의 next_page_token으로 다음 페이지를 조회한다. 위치 바이어스는 원 검색 세션에 이미 반영돼 있어 재전달 불필요. */
    public GoogleTextSearchResult searchNextPage(String pageToken) {
        ensureKeyConfigured();
        String encodedKey = URLEncoder.encode(googleApiKey, StandardCharsets.UTF_8);
        String encodedToken = URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
        String url = TEXT_SEARCH_URL + "?pagetoken=" + encodedToken + "&key=" + encodedKey;
        return parseTextSearch(callHttpWithRetry(url));
    }

    private void ensureKeyConfigured() {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            log.warn("Google Places API 키가 설정되지 않았습니다.");
            throw new GooglePlacesApiCallFailedException();
        }
    }

    private String buildTextSearchUrl(String query, Double lat, Double lng) {
        String encodedKey = URLEncoder.encode(googleApiKey, StandardCharsets.UTF_8);
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(TEXT_SEARCH_URL)
                .append("?query=").append(encodedQuery)
                .append("&language=ko");
        if (lat != null && lng != null) {
            url.append("&location=").append(lat).append(',').append(lng);
            url.append("&radius=").append(BIAS_RADIUS_METERS);
        }
        url.append("&key=").append(encodedKey);
        return url.toString();
    }

    /** 타임아웃/5xx는 1회만 재시도한다(서비스키 오류 등 4xx는 재시도해도 결과가 같으므로 즉시 실패 처리). TourApiClient와 동일 패턴. */
    private String callHttpWithRetry(String url) {
        try {
            return restTemplate.getForObject(URI.create(url), String.class);
        } catch (HttpClientErrorException e) {
            log.warn("Google Places API 요청 오류(재시도 안 함): {}", e.getMessage());
            throw new GooglePlacesApiCallFailedException();
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("Google Places API 호출 실패, 1회 재시도: {}", e.getMessage());
            try {
                return restTemplate.getForObject(URI.create(url), String.class);
            } catch (Exception retryEx) {
                log.warn("Google Places API 재시도도 실패: {}", retryEx.getMessage());
                throw new GooglePlacesApiCallFailedException();
            }
        } catch (Exception e) {
            log.warn("Google Places API 호출 중 예상치 못한 오류: {}", e.getMessage());
            throw new GooglePlacesApiCallFailedException();
        }
    }

    private GoogleTextSearchResult parseTextSearch(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("Google Places 응답 파싱 실패: {}", e.getMessage());
            throw new GooglePlacesApiCallFailedException();
        }

        String status = root.path("status").asText("");
        // ZERO_RESULTS는 정상 상황(검색 결과 없음)이지 오류가 아니다.
        if (!"OK".equals(status) && !"ZERO_RESULTS".equals(status)) {
            log.warn("Google Places API status 오류: {} {}", status, root.path("error_message").asText(""));
            throw new GooglePlacesApiCallFailedException();
        }

        List<GoogleTextSearchItem> items = new ArrayList<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                items.add(parseItem(result));
            }
        }

        return new GoogleTextSearchResult(items, textOrNull(root.path("next_page_token")));
    }

    private GoogleTextSearchItem parseItem(JsonNode result) {
        JsonNode location = result.path("geometry").path("location");

        String photoReference = null;
        JsonNode photos = result.path("photos");
        if (photos.isArray() && !photos.isEmpty()) {
            photoReference = textOrNull(photos.get(0).path("photo_reference"));
        }

        return new GoogleTextSearchItem(
                textOrNull(result.path("place_id")),
                textOrNull(result.path("name")),
                textOrNull(result.path("formatted_address")),
                doubleOrNull(location.path("lat")),
                doubleOrNull(location.path("lng")),
                photoReference);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private static Double doubleOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Text Search 응답 1건. {@code photoReference}는 최종 API 응답에는 노출되지 않고
     * 사진 파이프라인(GooglePlaceImageWorker) 내부에서만 쓰인다.
     */
    public record GoogleTextSearchItem(
            String placeId,
            String name,
            String address,
            Double lat,
            Double lng,
            String photoReference) {
    }

    public record GoogleTextSearchResult(List<GoogleTextSearchItem> items, String nextPageToken) {
    }
}
