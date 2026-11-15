package com.planmate.domain.destination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신규 계층 노드로 장소 추천(GET /api/place)이 기존 경로 그대로 동작하는지 — 코드 변경 없이 매핑만으로
 * 도 시도 노드·일반 시군·묶음 노드가 맞는 범위를 조회하는지 tourdata 직접 집계와 대조한다(B_계층_상세.md §6·§7-3).
 *
 * <p>tourdata 캐시 DB가 필요해 {@code @Tag("external")} — 로컬에서 {@code ./gradlew externalTest}로 실행.
 */
@Tag("external")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DestinationHierarchyPlaceIntegrationTest {

    private static final List<String> ATTRACTION_TYPES = List.of("12", "14");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DestinationRepository destinationRepository;

    @Autowired
    @Qualifier("tourdataJdbcTemplate")
    private NamedParameterJdbcTemplate tourdata;

    @Test
    @DisplayName("도 시도 노드(경기) - 매핑 (41, NULL)로 경기도 전체 관광지를 조회한다")
    void sidoNode_wholeProvince() throws Exception {
        long expected = tourdataCount("SELECT count(*) FROM place_list WHERE content_type_id IN (:types) AND ldong_regn_cd = '41'");

        assertThat(totalCountOf("경기")).isEqualTo(expected).isPositive();
    }

    @Test
    @DisplayName("일반 시군 노드(파주) - 매핑 (41, 480) 한 코드 범위만 조회한다")
    void sigunguNode_singleCode() throws Exception {
        long expected = tourdataCount("""
                SELECT count(*) FROM place_list
                WHERE content_type_id IN (:types) AND ldong_regn_cd = '41' AND ldong_signgu_cd = '480'
                """);

        assertThat(totalCountOf("파주")).isEqualTo(expected).isPositive();
    }

    @Test
    @DisplayName("묶음 노드(화성) - 대표코드 + 구 4개 합집합을 조회한다(대표코드에 데이터가 몰린 경우, §4-2-1)")
    void bundleNode_representativePlusDistricts() throws Exception {
        long expected = tourdataCount("""
                SELECT count(*) FROM place_list
                WHERE content_type_id IN (:types) AND ldong_regn_cd = '41'
                  AND ldong_signgu_cd IN ('590', '591', '593', '595', '597')
                """);
        long representativeOnly = tourdataCount("""
                SELECT count(*) FROM place_list
                WHERE content_type_id IN (:types) AND ldong_regn_cd = '41' AND ldong_signgu_cd = '590'
                """);

        // 구 코드 데이터는 수집 시점에 따라 0일 수 있어(로컬 덤프 vs 운영) "이상"으로만 본다 — 핵심은 합집합 일치
        assertThat(totalCountOf("화성")).isEqualTo(expected).isGreaterThanOrEqualTo(representativeOnly);
    }

    private long totalCountOf(String destinationName) throws Exception {
        Integer id = destinationRepository.findByDestinationName(destinationName).orElseThrow().getDestinationId();
        String body = mockMvc.perform(get("/api/place?destinationId=" + id + "&category=ATTRACTION"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("totalCount").asLong();
    }

    private long tourdataCount(String sql) {
        Long value = tourdata.queryForObject(sql, new MapSqlParameterSource("types", ATTRACTION_TYPES), Long.class);
        return value == null ? 0 : value;
    }
}
