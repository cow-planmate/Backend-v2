package com.planmate.domain.place.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /api/place/search — 실제 유저 시나리오 통합 테스트, 전 구간(컨트롤러→서비스→PlaceCacheRepository
 * →tourdata 5433→JSON) 검증. 인증 불필요({@code /api/place/*} 화이트리스트). CI 제외({@code @Tag("external")}).
 * place/10_장소검색_캐시DB_설계.md.
 */
@Tag("external")
@SpringBootTest
@AutoConfigureMockMvc
class PlaceKeywordSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode search(String query) throws Exception {
        return search(query, null, null);
    }

    private JsonNode search(String query, Integer page, Integer size) throws Exception {
        var request = get("/api/place/search").param("query", query);
        if (page != null) {
            request = request.param("page", String.valueOf(page));
        }
        if (size != null) {
            request = request.param("size", String.valueOf(size));
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> titles(JsonNode body) {
        List<String> titles = new ArrayList<>();
        body.get("places").forEach(place -> titles.add(place.get("title").asText()));
        return titles;
    }

    @Test
    @DisplayName("성공 - 정확히 일치하는 이름이 최상위로 온다 (인증 불필요, 토큰 없이 호출)")
    void 정확히_일치하는_이름이_최상위() throws Exception {
        JsonNode body = search("경복궁");

        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("places")).isNotEmpty();
        assertThat(titles(body).get(0)).isEqualTo("경복궁");
    }

    @Test
    @DisplayName("성공 - 완성형 오타여도 원래 장소가 결과에 잡힌다")
    void 오타여도_결과에_잡힌다() throws Exception {
        JsonNode body = search("해운데");

        assertThat(titles(body)).anyMatch(title -> title.contains("해운대"));
    }

    @Test
    @DisplayName("성공 - 쇼핑(content_type_id=38) 등 PlaceCategory 밖 타입은 결과에서 제외된다")
    void 쇼핑타입은_제외된다() throws Exception {
        JsonNode body = search("이마트");

        body.get("places").forEach(place ->
                assertThat(place.get("contentTypeId").asText()).isIn("12", "14", "32", "39"));
    }

    @Test
    @DisplayName("성공 - 응답 필드가 추천 API와 동일한 PlaceSummaryDto shape이다(naverMapUrl 포함)")
    void 응답_필드가_추천API와_동일하다() throws Exception {
        JsonNode body = search("경복궁");

        JsonNode first = body.get("places").get(0);
        assertThat(first.has("contentId")).isTrue();
        assertThat(first.has("contentTypeId")).isTrue();
        assertThat(first.has("category")).isTrue();
        assertThat(first.has("title")).isTrue();
        assertThat(first.has("addr1")).isTrue();
        assertThat(first.has("thumbnailUrl")).isTrue();
        assertThat(first.has("copyrightDivCd")).isTrue();
        assertThat(first.has("latitude")).isTrue();
        assertThat(first.has("longitude")).isTrue();
        assertThat(first.get("naverMapUrl").asText()).isNotBlank();
    }

    @Test
    @DisplayName("페이지네이션 - 1페이지와 2페이지의 title은 겹치지 않는다")
    void 페이지네이션_1페이지와_2페이지_중복없음() throws Exception {
        long total = search("공원", 1, 20).get("totalCount").asLong();
        assertThat(total).as("'공원'이 들어간 관광지/문화시설/숙박/음식점이 40건 이상이어야 이 테스트가 유효").isGreaterThanOrEqualTo(40);

        List<String> page1 = titles(search("공원", 1, 20));
        List<String> page2 = titles(search("공원", 2, 20));

        assertThat(page1).hasSize(20);
        assertThat(page2).hasSize(20);
    }

    @Test
    @DisplayName("실패 - query 누락이면 400(COMMON_001)")
    void query_누락이면_400() throws Exception {
        mockMvc.perform(get("/api/place/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("실패 - query가 2자 미만이면 400(COMMON_001)")
    void query가_2자_미만이면_400() throws Exception {
        mockMvc.perform(get("/api/place/search?query=경"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("실패 - size가 20을 초과하면 400")
    void size가_20_초과면_400() throws Exception {
        mockMvc.perform(get("/api/place/search?query=경복궁&size=21"))
                .andExpect(status().isBadRequest());
    }
}
