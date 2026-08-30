package com.planmate.domain.place.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/place — 실제 유저 시나리오 통합 테스트.
 *
 * <p>앱 DB(planmate, Flyway V2 시드: 28개 목적지 + 38 지역코드 + 25 테마코드) + tourdata 캐시 DB(5433)를
 * 한 요청에서 같이 쓴다. 컨트롤러 → PlaceService → resolveMatchedThemes(앱 DB) → PlaceCacheRepository(tourdata)
 * → PlaceSearchResponse JSON 전 구간. 선호테마/지역/페이지네이션을 실제 데이터로 검증.
 *
 * <p>CI 러너엔 tourdata 컨테이너가 없어 {@code @Tag("external")}로 기본 test 에서 제외
 * (build.gradle: {@code excludeTags 'external'}). 로컬:
 * {@code docker compose -f docker-compose.local.yml up -d} 후 {@code ./gradlew externalTest}.
 * 03_추천장소_캐시전환_설계.md §8.
 */
@Tag("external")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlaceRecommendationIntegrationTest {

    // V2__seed.sql 첫 번째 destination = 서울(destination_id 1, ldong_regn_cd 11, signgu NULL). 쿼리스트링에 리터럴로 사용.

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PreferredThemeRepository preferredThemeRepository;
    @Autowired
    private UserPreferredThemeRepository userPreferredThemeRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    @Qualifier("tourdataJdbcTemplate")
    private NamedParameterJdbcTemplate tourdataJdbc;

    private String koreanFoodLoverToken;

    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("place-it-" + UUID.randomUUID() + "@example.com")
                .nickname("place-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        PreferredTheme koreanFood = preferredThemeRepository.findByCategory(PreferredThemeCategory.RESTAURANT).stream()
                .filter(theme -> "한식".equals(theme.getPreferredThemeName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("V2 시드에 식당/한식 테마가 없음"));
        userPreferredThemeRepository.saveAndFlush(UserPreferredTheme.builder()
                .user(user)
                .preferredTheme(koreanFood)
                .build());

        koreanFoodLoverToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());
    }

    private JsonNode getPlaces(String query, String token) throws Exception {
        var request = get("/api/place?" + query);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> contentIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("places").forEach(place -> ids.add(place.get("contentId").asText()));
        return ids;
    }

    private long rawCount(String contentTypeIdInClause) {
        Long n = tourdataJdbc.queryForObject(
                "SELECT count(*) FROM place_list WHERE ldong_regn_cd = '11' AND content_type_id IN (" + contentTypeIdInClause + ")",
                Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("비로그인 - 서울 음식점 추천: 200, 전부 contentTypeId=39, totalCount는 캐시 DB 실제 건수와 일치")
    void 비로그인_음식점_추천_기본() throws Exception {
        JsonNode body = getPlaces("destinationId=1&category=RESTAURANT", null);

        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("places")).isNotEmpty();
        assertThat(body.get("places").size()).isLessThanOrEqualTo(20);
        body.get("places").forEach(place -> {
            assertThat(place.get("contentTypeId").asText()).isEqualTo("39");
            assertThat(place.get("category").asText()).isEqualTo("RESTAURANT");
            assertThat(place.get("contentId").asText()).isNotBlank();
        });
        assertThat(body.get("totalCount").asLong()).isEqualTo(rawCount("'39'"));
        assertThat(body.get("hasNext").isBoolean()).isTrue();
    }

    @Test
    @DisplayName("비로그인 - ATTRACTION은 관광지(12)+문화시설(14)을 한 번에, totalCount는 두 타입 합계")
    void ATTRACTION은_12와_14를_함께_준다() throws Exception {
        JsonNode body = getPlaces("destinationId=1&category=ATTRACTION&size=20", null);

        assertThat(body.get("places")).isNotEmpty();
        body.get("places").forEach(place ->
                assertThat(place.get("contentTypeId").asText()).isIn("12", "14"));
        assertThat(body.get("totalCount").asLong()).isEqualTo(rawCount("'12','14'"));
    }

    @Test
    @DisplayName("로그인(한식 선호) - 매칭 장소(lcls_systm2=FD01)가 상위로 올라오고, totalCount는 비로그인과 동일(테마=필터 아님)")
    void 로그인_선호테마_한식이면_매칭장소가_상위() throws Exception {
        // 서울 음식점 1604건 중 FD01(한식) 807 / 비-FD01 797 — md5 셔플이면 top-20 에 FD01 이 절반쯤 섞임.
        JsonNode anonymous = getPlaces("destinationId=1&category=RESTAURANT&size=20", null);
        JsonNode themed = getPlaces("destinationId=1&category=RESTAURANT&size=20", koreanFoodLoverToken);

        long anonymousFd01 = countFd01(contentIds(anonymous));
        long themedFd01 = countFd01(contentIds(themed));

        // 테마는 WHERE 필터가 아니라 ORDER BY 랭크 → 전체 건수는 그대로
        assertThat(themed.get("totalCount").asLong()).isEqualTo(anonymous.get("totalCount").asLong());
        // 선호테마가 있으면 매칭 장소가 앞으로 — top-20 이 전부 FD01, 비로그인보다 확실히 많다
        assertThat(themedFd01).isEqualTo(20);
        assertThat(anonymousFd01).isLessThan(20);
        assertThat(themedFd01).isGreaterThan(anonymousFd01);

        // 순서 자체도 확인: 응답 안에서 FD01 이 비-FD01 보다 뒤에 나오면 안 됨
        boolean seenNonMatch = false;
        for (String contentId : contentIds(themed)) {
            boolean match = isFd01(contentId);
            if (!match) {
                seenNonMatch = true;
            } else {
                assertThat(seenNonMatch).as("한식(FD01) 장소가 비매칭 뒤에 나옴: " + contentId).isFalse();
            }
        }
    }

    private boolean isFd01(String contentId) {
        String lcls2 = tourdataJdbc.queryForObject(
                "SELECT lcls_systm2 FROM place_list WHERE content_id = :id", Map.of("id", contentId), String.class);
        return "FD01".equals(lcls2);
    }

    private long countFd01(List<String> contentIds) {
        return contentIds.stream().filter(this::isFd01).count();
    }

    @Test
    @DisplayName("페이지네이션 - 1페이지와 2페이지의 contentId 는 겹치지 않는다")
    void 페이지네이션_1페이지와_2페이지_중복없음() throws Exception {
        long total = getPlaces("destinationId=1&category=RESTAURANT", null).get("totalCount").asLong();
        assertThat(total).as("서울 음식점이 40건 이상이어야 이 테스트가 유효").isGreaterThanOrEqualTo(40);

        List<String> page1 = contentIds(getPlaces("destinationId=1&category=RESTAURANT&page=1&size=20", null));
        List<String> page2 = contentIds(getPlaces("destinationId=1&category=RESTAURANT&page=2&size=20", null));

        assertThat(page1).hasSize(20);
        assertThat(page2).hasSize(20);
        assertThat(page1).doesNotContainAnyElementsOf(page2);
    }

    @Test
    @DisplayName("실패 - 존재하지 않는 destinationId 는 404(DEST_001)")
    void 없는_destinationId면_404() throws Exception {
        mockMvc.perform(get("/api/place?destinationId=99999&category=RESTAURANT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEST_001"));
    }

    @Test
    @DisplayName("실패 - category 누락이면 400(COMMON_001)")
    void category_누락이면_400() throws Exception {
        mockMvc.perform(get("/api/place?destinationId=1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("실패 - size 가 20 을 초과하면 400")
    void size가_20_초과면_400() throws Exception {
        mockMvc.perform(get("/api/place?destinationId=1&category=RESTAURANT&size=21"))
                .andExpect(status().isBadRequest());
    }
}
