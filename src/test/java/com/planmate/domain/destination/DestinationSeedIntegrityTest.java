package com.planmate.domain.destination;

import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V16 시드 무결성 — 손으로 검증하기 어려운 170노드·213매핑 시드의 실수를 배포 전에 잡는다(B_계층_상세.md §11).
 * 실제 DB(Flyway 적용 결과) 기준.
 */
@SpringBootTest
@Transactional
class DestinationSeedIntegrityTest {

    // 기존 28개 — ID는 V2 시드 INSERT 순서. 플랜·커뮤니티 일정 스냅샷이 참조하므로 바뀌면 안 된다(§4-3)
    private static final List<String> EXISTING_28 = List.of(
            "서울", "인천", "수원", "가평", "강릉", "속초", "춘천", "평창", "양양", "대전",
            "공주", "부여", "태안", "전주", "여수", "순천", "광주", "담양", "목포", "부산",
            "대구", "경주", "안동", "통영", "거제", "남해", "제주", "서귀포");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DestinationRepository destinationRepository;

    @Autowired
    private DestinationRegionCodeCache destinationRegionCodeCache;

    @Test
    @DisplayName("노드 170개(시도 16 + 시군 154), 매핑 213행, 1차 노출 34개")
    void counts() {
        assertThat(count("SELECT count(*) FROM destination WHERE parent_id IS NULL")).isEqualTo(16);
        assertThat(count("SELECT count(*) FROM destination WHERE parent_id IS NOT NULL")).isEqualTo(154);
        assertThat(count("SELECT count(*) FROM destination_region_code")).isEqualTo(213);
        assertThat(count("SELECT count(*) FROM destination WHERE is_featured")).isEqualTo(34);
    }

    @Test
    @DisplayName("모든 노드에 매핑이 1행 이상 있다 — 없으면 장소 추천이 404(DestinationNotFoundException)")
    void everyNodeHasMapping() {
        assertThat(count("""
                SELECT count(*) FROM destination d
                WHERE NOT EXISTS (SELECT 1 FROM destination_region_code r WHERE r.destination_id = d.destination_id)
                """)).isZero();
    }

    @Test
    @DisplayName("트리 깊이 2 — 시군 노드의 부모는 항상 시도 노드")
    void parentIsAlwaysSido() {
        assertThat(count("""
                SELECT count(*) FROM destination c
                JOIN destination p ON p.destination_id = c.parent_id
                WHERE p.parent_id IS NOT NULL
                """)).isZero();
    }

    @Test
    @DisplayName("시도 노드 매핑은 (시도코드, NULL) 1행, 시군 노드 매핑엔 NULL 시군구가 없다")
    void mappingShapeByLevel() {
        assertThat(count("""
                SELECT count(*) FROM destination d
                WHERE d.parent_id IS NULL
                  AND (SELECT count(*) FROM destination_region_code r
                       WHERE r.destination_id = d.destination_id AND r.ldong_signgu_cd IS NULL) <> 1
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM destination_region_code r
                JOIN destination d ON d.destination_id = r.destination_id
                WHERE d.parent_id IS NOT NULL AND r.ldong_signgu_cd IS NULL
                """)).isZero();
    }

    @Test
    @DisplayName("노드 이름은 전체에서도 유일(§7-7 — 시도 노드 '제주도'로 '제주'와 겹치지 않음)")
    void namesGloballyUnique() {
        assertThat(count("SELECT count(*) - count(DISTINCT destination_name) FROM destination")).isZero();
        assertThat(destinationRepository.findByDestinationName("제주도")).get()
                .extracting(Destination::isSido).isEqualTo(true);
    }

    @Test
    @DisplayName("기존 28개 — ID 1~28·이름 그대로, 전부 1차 노출")
    void existing28Preserved() {
        for (int i = 0; i < EXISTING_28.size(); i++) {
            Destination d = destinationRepository.findById(i + 1).orElseThrow();
            assertThat(d.getDestinationName()).isEqualTo(EXISTING_28.get(i));
            assertThat(d.isFeatured()).as(d.getDestinationName()).isTrue();
        }
    }

    @Test
    @DisplayName("묶음 매핑(§4-2-1) — 화성은 대표코드+구 4개, 광주는 구 5개, 포항은 대표+구 2개")
    void bundleMappings() {
        assertThat(signguCodesOf("화성")).containsExactlyInAnyOrder("590", "591", "593", "595", "597");
        assertThat(signguCodesOf("광주")).containsExactlyInAnyOrder("210", "240", "270", "300", "330");
        assertThat(signguCodesOf("포항")).containsExactlyInAnyOrder("110", "111", "113");
        assertThat(signguCodesOf("파주")).containsExactly("480");
    }

    @Test
    @DisplayName("기동 시 로드되는 지역코드 캐시에 신규 노드 매핑도 들어 있다(장소 추천·챗봇이 쓰는 경로)")
    void regionCodeCacheCoversNewNodes() {
        Integer gyeonggi = destinationRepository.findByDestinationName("경기").orElseThrow().getDestinationId();
        Integer paju = destinationRepository.findByDestinationName("파주").orElseThrow().getDestinationId();

        assertThat(destinationRegionCodeCache.get(gyeonggi))
                .extracting(DestinationRegionCode::getLdongRegnCd, DestinationRegionCode::getLdongSignguCd)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("41", null));
        assertThat(destinationRegionCodeCache.get(paju))
                .extracting(DestinationRegionCode::getLdongSignguCd)
                .containsExactly("480");
    }

    // ---------- UNIQUE NULLS NOT DISTINCT (parent_id, destination_name) 방어 실측 ----------

    @Test
    @DisplayName("DB 제약 - 시도 노드(부모 NULL)끼리 같은 이름은 거부된다(NULLS NOT DISTINCT)")
    void unique_rejectsDuplicateSidoName() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO destination (destination_name, latitude, longitude, parent_id, is_featured, display_order)
                VALUES ('경기', 37, 127, NULL, false, 999)
                """)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_destination_parent_name");
    }

    @Test
    @DisplayName("DB 제약 - 같은 부모 아래 같은 이름은 거부된다")
    void unique_rejectsDuplicateNameUnderSameParent() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO destination (destination_name, latitude, longitude, parent_id, is_featured, display_order)
                SELECT '파주', 37, 127, parent_id, false, 999 FROM destination WHERE destination_name = '파주'
                """)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_destination_parent_name");
    }

    @Test
    @DisplayName("DB 제약 - 부모가 다르면 같은 이름도 허용된다(동명 시군구 대비)")
    void unique_allowsSameNameUnderDifferentParent() {
        int inserted = jdbcTemplate.update("""
                INSERT INTO destination (destination_name, latitude, longitude, parent_id, is_featured, display_order)
                SELECT '파주', 37, 127, destination_id, false, 999 FROM destination
                WHERE destination_name = '강원' AND parent_id IS NULL
                """);
        assertThat(inserted).isEqualTo(1);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private List<String> signguCodesOf(String name) {
        Integer id = destinationRepository.findByDestinationName(name).orElseThrow().getDestinationId();
        return destinationRegionCodeCache.get(id).stream()
                .map(DestinationRegionCode::getLdongSignguCd)
                .collect(Collectors.toList());
    }
}
