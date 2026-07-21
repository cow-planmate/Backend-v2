package com.planmate.common.externalAPI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.exception.domain.TourApiCallFailedException;
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
 * 한국관광공사 TourAPI(KorService2) areaBasedList2 호출 클라이언트.
 * 지역코드(lDongRegnCd)+카테고리(contentTypeId) 기준 장소 목록을 조회한다.
 * project_ai/04_기능/place/04_추천장소_API_인프라설계.md 3장.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TourApiClient {

    @Qualifier("tourApiRestTemplate")
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${api.tour.key:}")
    private String tourApiKey;
    @Value("${api.tour.base-url:https://apis.data.go.kr/B551011/KorService2}")
    private String tourApiBaseUrl;

    /**
     * areaBasedList2를 호출한다. lclsSystmLevel/lclsSystmCd가 둘 다 있으면 레벨에 맞는
     * lclsSystm1/2/3 파라미터로 매핑해서 필터링하고, 없으면(null) 필터 없이 호출한다.
     * 서비스키 누락, HTTP 실패(1회 재시도 후), resultCode 오류는 전부 TourApiCallFailedException으로 던진다.
     */
    public AreaBasedListResult getAreaBasedList(
            String lDongRegnCd, String lDongSignguCd,
            String contentTypeId,
            Integer lclsSystmLevel, String lclsSystmCd,
            int numOfRows, int pageNo) {

        if (tourApiKey == null || tourApiKey.isBlank()) {
            log.warn("TourAPI 서비스키가 설정되지 않았습니다.");
            throw new TourApiCallFailedException();
        }

        String url = buildAreaBasedListUrl(lDongRegnCd, lDongSignguCd, contentTypeId,
                lclsSystmLevel, lclsSystmCd, numOfRows, pageNo);
        String body = callHttpWithRetry(url);
        return parseAreaBasedList(body);
    }

    private String buildAreaBasedListUrl(
            String lDongRegnCd, String lDongSignguCd, String contentTypeId,
            Integer lclsSystmLevel, String lclsSystmCd, int numOfRows, int pageNo) {

        String encodedKey = URLEncoder.encode(tourApiKey, StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(tourApiBaseUrl)
                .append("/areaBasedList2")
                .append("?serviceKey=").append(encodedKey)
                .append("&MobileOS=ETC")
                .append("&MobileApp=PlanMate")
                .append("&_type=json")
                .append("&numOfRows=").append(numOfRows)
                .append("&pageNo=").append(pageNo)
                .append("&lDongRegnCd=").append(URLEncoder.encode(lDongRegnCd, StandardCharsets.UTF_8));

        if (lDongSignguCd != null && !lDongSignguCd.isBlank()) {
            url.append("&lDongSignguCd=").append(URLEncoder.encode(lDongSignguCd, StandardCharsets.UTF_8));
        }
        if (contentTypeId != null && !contentTypeId.isBlank()) {
            url.append("&contentTypeId=").append(URLEncoder.encode(contentTypeId, StandardCharsets.UTF_8));
        }
        if (lclsSystmLevel != null && lclsSystmCd != null && !lclsSystmCd.isBlank()) {
            String paramName = switch (lclsSystmLevel) {
                case 1 -> "lclsSystm1";
                case 2 -> "lclsSystm2";
                case 3 -> "lclsSystm3";
                default -> throw new IllegalArgumentException("lclsSystmLevel은 1~3만 허용됩니다: " + lclsSystmLevel);
            };
            url.append('&').append(paramName).append('=').append(URLEncoder.encode(lclsSystmCd, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /**
     * 타임아웃/5xx는 1회만 재시도한다(서비스키 오류 등 4xx는 재시도해도 결과가 같으므로 즉시 실패 처리).
     */
    private String callHttpWithRetry(String url) {
        try {
            return restTemplate.getForObject(URI.create(url), String.class);
        } catch (HttpClientErrorException e) {
            log.warn("TourAPI 요청 오류(재시도 안 함): {}", e.getMessage());
            throw new TourApiCallFailedException();
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("TourAPI 호출 실패, 1회 재시도: {}", e.getMessage());
            try {
                return restTemplate.getForObject(URI.create(url), String.class);
            } catch (Exception retryEx) {
                log.warn("TourAPI 재시도도 실패: {}", retryEx.getMessage());
                throw new TourApiCallFailedException();
            }
        } catch (Exception e) {
            log.warn("TourAPI 호출 중 예상치 못한 오류: {}", e.getMessage());
            throw new TourApiCallFailedException();
        }
    }

    private AreaBasedListResult parseAreaBasedList(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("TourAPI 응답 파싱 실패: {}", e.getMessage());
            throw new TourApiCallFailedException();
        }

        JsonNode response = root.path("response");
        JsonNode header = response.path("header");
        String resultCode = header.path("resultCode").asText("");
        if (!"0000".equals(resultCode)) {
            log.warn("TourAPI resultCode 오류: {} {}", resultCode, header.path("resultMsg").asText(""));
            throw new TourApiCallFailedException();
        }

        JsonNode bodyNode = response.path("body");
        long totalCount = bodyNode.path("totalCount").asLong(0);
        JsonNode itemNode = bodyNode.path("items").path("item");

        List<AreaBasedListItem> items = new ArrayList<>();
        if (itemNode.isArray()) {
            for (JsonNode item : itemNode) {
                items.add(parseItem(item));
            }
        } else if (itemNode.isObject()) {
            // 결과가 1건이면 TourAPI가 배열이 아니라 단일 객체로 반환하는 경우가 있어 방어적으로 처리.
            items.add(parseItem(itemNode));
        }

        return new AreaBasedListResult(items, totalCount);
    }

    private AreaBasedListItem parseItem(JsonNode item) {
        return new AreaBasedListItem(
                textOrNull(item.path("contentid")),
                textOrNull(item.path("contenttypeid")),
                textOrNull(item.path("title")),
                textOrNull(item.path("addr1")),
                textOrNull(item.path("addr2")),
                doubleOrNull(item.path("mapx")),
                doubleOrNull(item.path("mapy")),
                textOrNull(item.path("firstimage2")),
                textOrNull(item.path("cpyrhtDivCd")),
                textOrNull(item.path("lDongRegnCd")));
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
     * areaBasedList2 응답 1건 — 02_확정_필드_선정.md "1. 공용 필드" 그대로.
     * mapX/mapY는 표시용이 아니라 내부 계산용으로만 보유한다.
     */
    public record AreaBasedListItem(
            String contentId,
            String contentTypeId,
            String title,
            String addr1,
            String addr2,
            Double mapX,
            Double mapY,
            String thumbnailUrl,
            String copyrightDivCd,
            String ldongRegnCd) {
    }

    public record AreaBasedListResult(List<AreaBasedListItem> items, long totalCount) {
    }
}
