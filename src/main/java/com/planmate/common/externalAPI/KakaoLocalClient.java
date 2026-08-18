package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.planmate.common.exception.domain.KakaoLocalCallFailedException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로컬 키워드 검색 클라이언트.
 *
 * TourAPI가 관광공사에 등록된 장소만 아는 것과 달리, 카페·식당 같은 일반 상호까지 찾는다.
 * REST 키는 서버에만 둔다 — 브라우저에 내리면 그대로 도용돼 쿼터가 남의 트래픽으로 소진된다.
 */
@Component
@Slf4j
public class KakaoLocalClient {

    private static final String SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";

    private final RestClient restClient;
    private final String restApiKey;

    public KakaoLocalClient(RestClient.Builder builder,
                            @Value("${api.kakao.rest-key:}") String restApiKey) {
        this.restClient = builder.build();
        this.restApiKey = restApiKey;
    }

    public boolean isConfigured() {
        return !restApiKey.isBlank();
    }

    /** 검색어와 일치하는 장소 목록. 결과가 없으면 빈 리스트. */
    public List<KakaoPlace> searchKeyword(String query, int size) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(SEARCH_URL, uri -> uri.queryParam("query", query).queryParam("size", size).build())
                    .header("Authorization", "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("카카오 로컬 검색 실패: query={}", query, e);
            throw new KakaoLocalCallFailedException();
        }

        List<KakaoPlace> places = new ArrayList<>();
        JsonNode documents = body == null ? null : body.get("documents");
        if (documents != null) {
            for (JsonNode d : documents) {
                String road = text(d, "road_address_name");
                String jibun = text(d, "address_name");
                places.add(new KakaoPlace(
                        text(d, "id"),
                        text(d, "place_name"),
                        road != null ? road : jibun,
                        jibun,
                        text(d, "phone"),
                        text(d, "category_name"),
                        text(d, "place_url"),
                        // 카카오는 x=경도, y=위도다. 뒤집으면 지도에 엉뚱한 곳이 찍힌다.
                        parse(text(d, "y")),
                        parse(text(d, "x"))
                ));
            }
        }
        return places;
    }

    public record KakaoPlace(
            String id,
            String name,
            String address,
            String jibunAddress,
            String phone,
            String category,
            String url,
            Double lat,
            Double lng
    ) {}

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static Double parse(String v) {
        if (v == null) return null;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
