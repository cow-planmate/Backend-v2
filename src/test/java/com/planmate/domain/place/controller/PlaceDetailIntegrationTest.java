package com.planmate.domain.place.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /api/place/{contentId} — 실제 유저 시나리오, 전 구간(컨트롤러→서비스→앱조인→tourdata 5433→JSON) 검증.
 * 비로그인 호출({@code /api/place/*} 화이트리스트). CI 제외({@code @Tag("external")}).
 *
 * <p>잘 알려진 장소(경복궁 등)의 안정적 속성으로 값 변환(HTML 스트립·URL 추출·요금 "0"→null·
 * 부대시설 라벨·12/14 alias 통일)이 실제로 일어나는지 확인한다. 각 케이스는 대상 행이 아직 있는지
 * {@code assumeTrue}로 방어(최신화로 사라지면 skip). 05_장소상세_API_설계.md §13, 기능_구현_표준.md §16-5.
 */
@Tag("external")
@SpringBootTest
@AutoConfigureMockMvc
class PlaceDetailIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("tourdataJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    private boolean isType(String contentId, String contentTypeId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM place_list WHERE content_id = :id AND content_type_id = :t",
                Map.of("id", contentId, "t", contentTypeId), Long.class);
        return n != null && n > 0;
    }

    private String oneContentId(String where) {
        return jdbc.queryForList("SELECT content_id FROM place_list WHERE " + where + " LIMIT 1",
                Map.of(), String.class).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("관광지(12) 경복궁 - ATTRACTION 블록만, 좌표/연락처/주차 실값, useFee는 null(12)")
    void 경복궁_관광지_상세() throws Exception {
        assumeTrue(isType("126508", "12"), "126508(경복궁)이 12가 아니면 skip");

        mockMvc.perform(get("/api/place/{id}", "126508"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentId").value("126508"))
                .andExpect(jsonPath("$.contentTypeId").value("12"))
                .andExpect(jsonPath("$.category").value("ATTRACTION"))
                .andExpect(jsonPath("$.title").value("경복궁"))
                .andExpect(jsonPath("$.latitude").value(37.5760307))
                .andExpect(jsonPath("$.longitude").value(126.97672187))
                .andExpect(jsonPath("$.imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.thumbnailUrl").isNotEmpty())
                .andExpect(jsonPath("$.homepage").value(containsString("royal.khs.go.kr")))
                .andExpect(jsonPath("$.images").isArray())
                .andExpect(jsonPath("$.attraction").exists())
                .andExpect(jsonPath("$.attraction.infoCenter").value("02-3700-3900"))
                .andExpect(jsonPath("$.attraction.parking").value(containsString("승용차 240대")))
                .andExpect(jsonPath("$.attraction.useFee").doesNotExist())     // 12 → null
                .andExpect(jsonPath("$.attraction.infoItems").isArray())
                .andExpect(jsonPath("$.accommodation").doesNotExist())
                .andExpect(jsonPath("$.restaurant").doesNotExist());
    }

    @Test
    @DisplayName("문화시설(14) 가나아트센터 - category=ATTRACTION, ~culture 컬럼이 같은 필드로 매핑(alias 통일), useFee/parkingFee 채움")
    void 문화시설_alias_통일() throws Exception {
        assumeTrue(isType("129854", "14"), "129854(가나아트센터)가 14가 아니면 skip");

        mockMvc.perform(get("/api/place/{id}", "129854"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentTypeId").value("14"))
                .andExpect(jsonPath("$.category").value("ATTRACTION"))
                .andExpect(jsonPath("$.attraction.useTime").value("화요일~일요일 10:00~19:00"))
                .andExpect(jsonPath("$.attraction.useFee").value("3,000원"))
                .andExpect(jsonPath("$.attraction.parkingFee").value("무료"))
                .andExpect(jsonPath("$.attraction.infoCenter").value("02-720-1020"));
    }

    @Test
    @DisplayName("homepage에 <a href> 통짜 HTML이면 href의 URL만 추출(보이는 텍스트 아님)")
    void homepage_href_URL만_추출() throws Exception {
        assumeTrue(isType("1000981", "14"), "1000981이 14가 아니면 skip");

        mockMvc.perform(get("/api/place/{id}", "1000981"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homepage")
                        .value("https://www.utour.go.kr/01198/01963/01214.web?idx=1688&amode=view&"));
    }

    @Test
    @DisplayName("음식점(39) 가고파식당 - RESTAURANT 블록만, treatMenu 원문, openTime의 <br>는 개행으로 스트립, 이미지 없으면 []")
    void 음식점_상세_HTML스트립() throws Exception {
        assumeTrue(isType("2572443", "39"), "2572443(가고파식당)이 39가 아니면 skip");

        mockMvc.perform(get("/api/place/{id}", "2572443"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("RESTAURANT"))
                .andExpect(jsonPath("$.images").isEmpty())
                .andExpect(jsonPath("$.restaurant.firstMenu").value("모둠회"))
                .andExpect(jsonPath("$.restaurant.treatMenu").value("매운탕 / 정식 / 돈가스 / 두루치기 등"))
                .andExpect(jsonPath("$.restaurant.openTime")
                        .value("11:00~22:00\n※ 정확한 영업시간은 방문 전 전화 문의 요망"))
                .andExpect(jsonPath("$.restaurant.restDate").value("연중무휴"))
                .andExpect(jsonPath("$.attraction").doesNotExist())
                .andExpect(jsonPath("$.accommodation").doesNotExist());
    }

    @Test
    @DisplayName("숙박(32) 홀리데이인 광주 - ACCOMMODATION 블록만, 부대시설 코드→한글 라벨, 객실요금 '0'→null, priceBaseDate 필드 없음")
    void 숙박_상세_요금0_처리_부대시설_라벨() throws Exception {
        assumeTrue(isType("1177256", "32"), "1177256이 32가 아니면 skip");

        mockMvc.perform(get("/api/place/{id}", "1177256"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("ACCOMMODATION"))
                .andExpect(jsonPath("$.accommodation.checkInTime").value("15:00"))
                .andExpect(jsonPath("$.accommodation.roomCount").value("203실"))
                .andExpect(jsonPath("$.accommodation.amenities", hasItems("사우나", "피트니스 센터")))
                .andExpect(jsonPath("$.accommodation.rooms[0].roomTitle").value("Standard"))
                .andExpect(jsonPath("$.accommodation.rooms[0].maxCount").value("3"))
                // 원본 요금이 전부 "0"(정보없음) → 전부 null, 0 은 응답에 없어야 한다
                .andExpect(jsonPath("$.accommodation.rooms[0].offSeasonWeekdayFee").doesNotExist())
                .andExpect(jsonPath("$.accommodation.rooms[?(@.peakSeasonWeekdayFee == 0)]").isEmpty())
                .andExpect(jsonPath("$.accommodation.priceBaseDate").doesNotExist())
                .andExpect(jsonPath("$.attraction").doesNotExist());
    }

    @Test
    @DisplayName("실패 - 존재하지 않는 content_id는 404(PLACE_006)")
    void 없는_장소면_404() throws Exception {
        mockMvc.perform(get("/api/place/{id}", "no-such-content-id-xyz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_006"));
    }

    @Test
    @DisplayName("실패 - 상세 미지원 타입(축제 15)은 404(PLACE_006)")
    void 축제는_상세_미지원_404() throws Exception {
        String festival = oneContentId("content_type_id = '15'");
        assumeTrue(festival != null);

        mockMvc.perform(get("/api/place/{id}", festival))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_006"));
    }

    @Test
    @DisplayName("이미지는 상한 10장 이하(현행 데이터 최대 10장)")
    void 이미지_10장_상한() throws Exception {
        String contentId = jdbc.queryForList(
                "SELECT content_id FROM place_image GROUP BY content_id ORDER BY count(*) DESC LIMIT 1",
                Map.of(), String.class).stream().findFirst().orElse(null);
        assumeTrue(contentId != null, "이미지 보유 장소가 없으면 skip");

        mockMvc.perform(get("/api/place/{id}", contentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(lessThanOrEqualTo(10)));
    }
}
