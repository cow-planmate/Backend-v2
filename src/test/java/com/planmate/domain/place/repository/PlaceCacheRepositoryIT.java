package com.planmate.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.planmate.domain.place.dto.PlaceSearchCriteria;
import com.planmate.domain.place.dto.PlaceSearchCriteria.RegionCode;
import com.planmate.domain.place.dto.PlaceSearchCriteria.ThemeCode;
import com.planmate.domain.place.repository.PlaceCacheRepository.PlaceRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code PlaceCacheRepository} SQL 로직 검증 — 실제 tourdata 캐시(localhost:5433, docker-compose.local.yml).
 * CI 러너엔 그 컨테이너가 없어 {@code @Tag("external")}로 기본 {@code test}에서 제외(build.gradle).
 * 로컬: {@code docker compose -f docker-compose.local.yml up -d} 후 {@code ./gradlew externalTest}.
 *
 * <p>단언은 정확한 건수가 아니라 범위/불변식으로 — 최신화 잡이 로컬 데이터를 바꿀 수 있으므로.
 * 03_추천장소_캐시전환_설계.md §8-1.
 */
@Tag("external")
@SpringBootTest
class PlaceCacheRepositoryIT {

    private static final int SEOUL_DESTINATION_ID = 1;
    private static final RegionCode SEOUL = new RegionCode("11", null);           // 시/도 전체(signgu null)
    private static final RegionCode SUWON_JANGAN = new RegionCode("41", "110");   // 수원 장안구
    private static final RegionCode SUWON_GWONSEON = new RegionCode("41", "111"); // 수원 권선구

    @Autowired
    private PlaceCacheRepository repository;

    @Autowired
    @Qualifier("tourdataJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    private PlaceSearchCriteria criteria(List<RegionCode> regions, List<String> contentTypeIds,
            List<ThemeCode> themes, int page, int size) {
        return new PlaceSearchCriteria(SEOUL_DESTINATION_ID, contentTypeIds, regions, themes, page, size);
    }

    @Test
    void 지역_카테고리로_조회하면_해당_타입_행만_페이지크기만큼_나온다() {
        PlaceSearchCriteria c = criteria(List.of(SEOUL), List.of("32"), List.of(), 1, 20);

        List<PlaceRow> rows = repository.search(c);
        long total = repository.count(c);

        assertThat(rows).isNotEmpty().hasSizeLessThanOrEqualTo(20);
        assertThat(rows).allSatisfy(r -> assertThat(r.contentTypeId()).isEqualTo("32"));
        assertThat(total).isGreaterThanOrEqualTo(rows.size());
        assertThat(rows).allSatisfy(r -> assertThat(r.contentId()).isNotBlank());
    }

    @Test
    void ATTRACTION은_12와_14를_한번에_섞어_조회한다() {
        PlaceSearchCriteria c = criteria(List.of(SEOUL), List.of("12", "14"), List.of(), 1, 20);

        List<PlaceRow> rows = repository.search(c);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.contentTypeId()).isIn("12", "14"));
    }

    @Test
    void signgu가_null이면_시도_전체를_커버한다() {
        long province = repository.count(criteria(List.of(new RegionCode("41", null)), List.of("39"), List.of(), 1, 20));
        long oneSigngu = repository.count(criteria(List.of(SUWON_JANGAN), List.of("39"), List.of(), 1, 20));

        assertThat(province).isGreaterThan(oneSigngu);
    }

    @Test
    void 지역코드_여러개면_서로소_signgu의_합집합이다() {
        long jangan = repository.count(criteria(List.of(SUWON_JANGAN), List.of("39"), List.of(), 1, 20));
        long gwonseon = repository.count(criteria(List.of(SUWON_GWONSEON), List.of("39"), List.of(), 1, 20));
        long union = repository.count(criteria(List.of(SUWON_JANGAN, SUWON_GWONSEON), List.of("39"), List.of(), 1, 20));

        // 한 장소는 signgu 하나뿐 → 서로 다른 signgu 두 개는 disjoint → 합집합 = 합
        assertThat(union).isEqualTo(jangan + gwonseon);
    }

    @Test
    void 선호테마는_필터가_아니라_정렬랭크라_totalCount가_같다() {
        List<RegionCode> seoul = List.of(SEOUL);
        long withoutTheme = repository.count(criteria(seoul, List.of("39"), List.of(), 1, 20));
        long withTheme = repository.count(criteria(seoul, List.of("39"), List.of(new ThemeCode(2, "FD01")), 1, 20));

        assertThat(withTheme).isEqualTo(withoutTheme);
    }

    @Test
    void 선호테마_매치_행이_비매치_행보다_먼저_나온다() {
        PlaceSearchCriteria c = criteria(List.of(SEOUL), List.of("39"), List.of(new ThemeCode(2, "FD01")), 1, 40);

        List<PlaceRow> rows = repository.search(c);

        boolean seenNonMatch = false;
        for (PlaceRow row : rows) {
            String lcls2 = jdbc.queryForObject(
                    "SELECT lcls_systm2 FROM place_list WHERE content_id = :id",
                    Map.of("id", row.contentId()), String.class);
            boolean match = "FD01".equals(lcls2);
            if (!match) {
                seenNonMatch = true;
            } else {
                assertThat(seenNonMatch)
                        .as("테마 매치(FD01) 행이 비매치 행보다 뒤에 나옴: " + row.contentId())
                        .isFalse();
            }
        }
    }

    @Test
    void 같은_criteria는_매번_같은_순서를_준다() {
        PlaceSearchCriteria c = criteria(List.of(SEOUL), List.of("12", "14"), List.of(), 1, 30);

        List<String> first = repository.search(c).stream().map(PlaceRow::contentId).toList();
        List<String> second = repository.search(c).stream().map(PlaceRow::contentId).toList();

        assertThat(second).containsExactlyElementsOf(first);
    }

    @Test
    void 페이지_경계에_중복이나_누락이_없다() {
        long total = repository.count(criteria(List.of(SEOUL), List.of("39"), List.of(), 1, 20));
        // 서울 음식점은 40건 이상이라는 전제(현행 데이터 기준 1,000건대)
        assertThat(total).isGreaterThanOrEqualTo(40);

        List<String> page1 = repository.search(criteria(List.of(SEOUL), List.of("39"), List.of(), 1, 20))
                .stream().map(PlaceRow::contentId).toList();
        List<String> page2 = repository.search(criteria(List.of(SEOUL), List.of("39"), List.of(), 2, 20))
                .stream().map(PlaceRow::contentId).toList();

        assertThat(page1).hasSize(20);
        assertThat(page2).hasSize(20);
        assertThat(page1).doesNotContainAnyElementsOf(page2);
    }

    @Test
    void count는_같은_필터의_독립_count쿼리와_일치한다() {
        PlaceSearchCriteria c = criteria(List.of(SEOUL), List.of("32"), List.of(new ThemeCode(3, "AC010100")), 1, 20);

        long repoCount = repository.count(c);
        Long rawCount = jdbc.queryForObject(
                "SELECT count(*) FROM place_list WHERE content_type_id = '32' AND ldong_regn_cd = '11'",
                Map.of(), Long.class);

        assertThat(repoCount).isEqualTo(rawCount);
    }
}
