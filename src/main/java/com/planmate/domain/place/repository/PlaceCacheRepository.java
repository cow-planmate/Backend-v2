package com.planmate.domain.place.repository;

import com.planmate.common.exception.domain.PlaceCacheQueryException;
import com.planmate.domain.place.dto.PlaceKeywordSearchCriteria;
import com.planmate.domain.place.dto.PlaceSearchCriteria;
import com.planmate.domain.place.enums.PlaceCategory;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 추천 장소 = tourdata 캐시 DB(읽기 전용)의 {@code place_list} 단일 테이블 조회.
 * 현행 TourAPI 실시간 호출(지역코드×선호테마 병렬 → 라운드로빈 병합 → 재계산형 페이지네이션)을
 * SQL 한 방으로 대체한다. 조인 없음.
 * project_ai/04_기능/ai/03_추천장소_캐시전환_설계.md.
 */
@Repository
@RequiredArgsConstructor
public class PlaceCacheRepository {

    @Qualifier("tourdataJdbcTemplate")
    private final NamedParameterJdbcTemplate jdbc;

    // 추측치, 실측 후 조정. place/10_장소검색_캐시DB_설계.md §7-3.
    private static final double SIMILARITY_THRESHOLD = 0.15;

    // 장소 검색도 PlaceCategory가 다루는 4개 타입(12/14/32/39)으로 제한한다 — place_list엔
    // 15(축제)/25(코스)/28(레포츠)/38(쇼핑)도 있는데, PlaceCategory.fromContentTypeId가 이 4개만
    // 알아서 그대로 두면 PlaceSummaryDto.from(row)에서 IllegalArgumentException이 던져진다
    // (실제로 전체 place_list의 약 36%가 이 4개 타입이라 우연이 아니라 거의 매번 재현됨).
    // blockCategory도 ATTRACTION/ACCOMMODATION/RESTAURANT/FREE/SEARCH뿐이라 쇼핑몰을 찾아도
    // 일정에 못 넣는 구조 — 검색 범위를 여기로 제한하는 게 자연스럽다는 판단(2026-09-20 결정).
    private static final List<String> SEARCHABLE_CONTENT_TYPE_IDS = Arrays.stream(PlaceCategory.values())
            .flatMap(category -> category.contentTypeIds().stream())
            .toList();

    private static final RowMapper<PlaceRow> ROW_MAPPER = (rs, rowNum) -> new PlaceRow(
            textOrNull(rs, "content_id"),
            textOrNull(rs, "content_type_id"),
            textOrNull(rs, "title"),
            textOrNull(rs, "addr1"),
            doubleOrNull(rs, "mapx"),
            doubleOrNull(rs, "mapy"),
            textOrNull(rs, "firstimage2"),
            textOrNull(rs, "cpyrht_div_cd"));

    /**
     * 요청 페이지(size/offset)만큼 조회. 정렬: 선호테마 매치 우선(랭크 0/1) → 결정적 셔플(md5) → content_id.
     * 테마는 WHERE 필터가 아니라 정렬 랭크다 — 매치가 부족하면 비매치가 자연히 이어진다(§4-5).
     */
    public List<PlaceRow> search(PlaceSearchCriteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("contentTypeIds", criteria.contentTypeIds())
                .addValue("destinationId", criteria.destinationId())
                .addValue("size", criteria.size())
                .addValue("offset", criteria.offset());

        String sql = "SELECT content_id, content_type_id, title, addr1, mapx, mapy, firstimage2, cpyrht_div_cd "
                + "FROM place_list "
                + "WHERE content_type_id IN (:contentTypeIds) "
                + "  AND (" + regionPredicate(criteria.regionCodes(), params) + ") "
                + "ORDER BY " + themeRankOrderTerm(criteria.themeCodes(), params)
                + "md5(content_id || CAST(:destinationId AS text)), content_id "
                + "LIMIT :size OFFSET :offset";

        try {
            return jdbc.query(sql, params, ROW_MAPPER);
        } catch (DataAccessException e) {
            throw new PlaceCacheQueryException(e);
        }
    }

    /** 필터(지역 + 카테고리, 테마 무관) 전체 건수 — 정확한 totalCount. */
    public long count(PlaceSearchCriteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("contentTypeIds", criteria.contentTypeIds());

        String sql = "SELECT count(*) FROM place_list "
                + "WHERE content_type_id IN (:contentTypeIds) "
                + "  AND (" + regionPredicate(criteria.regionCodes(), params) + ")";

        try {
            Long total = jdbc.queryForObject(sql, params, Long.class);
            return total == null ? 0L : total;
        } catch (DataAccessException e) {
            throw new PlaceCacheQueryException(e);
        }
    }

    /**
     * 이름으로 장소 찾기 — 지역 필터는 없이 전국 대상이지만, 카테고리는 {@code PlaceCategory}가
     * 다루는 4개 타입(관광지/문화시설/숙박/음식점)으로 제한한다(§ SEARCHABLE_CONTENT_TYPE_IDS).
     * 부분일치(ILIKE)는 항상 잡고, 유사도(음절/자모)는 오타 케이스를 추가로 잡는다.
     * 정렬은 둘 중 더 높은 유사도 내림차순. place/10_장소검색_캐시DB_설계.md §7-3.
     */
    public List<PlaceRow> searchByKeyword(PlaceKeywordSearchCriteria criteria) {
        MapSqlParameterSource params = keywordParams(criteria);

        String sql = "SELECT content_id, content_type_id, title, addr1, mapx, mapy, firstimage2, cpyrht_div_cd, "
                + "  GREATEST(similarity(title, :query), similarity(hangul_jamo(title), hangul_jamo(:query))) AS score "
                + "FROM place_list "
                + "WHERE " + keywordPredicate()
                + "ORDER BY score DESC, content_id "
                + "LIMIT :size OFFSET :offset";

        try {
            return jdbc.query(sql, params, ROW_MAPPER);
        } catch (DataAccessException e) {
            throw new PlaceCacheQueryException(e);
        }
    }

    /** {@code searchByKeyword}와 동일한 WHERE 조건의 전체 건수 — 정확한 totalCount. */
    public long countByKeyword(PlaceKeywordSearchCriteria criteria) {
        MapSqlParameterSource params = keywordParams(criteria);

        String sql = "SELECT count(*) FROM place_list WHERE " + keywordPredicate();

        try {
            Long total = jdbc.queryForObject(sql, params, Long.class);
            return total == null ? 0L : total;
        } catch (DataAccessException e) {
            throw new PlaceCacheQueryException(e);
        }
    }

    private static MapSqlParameterSource keywordParams(PlaceKeywordSearchCriteria criteria) {
        return new MapSqlParameterSource()
                .addValue("query", criteria.query())
                .addValue("likePattern", "%" + escapeLikeWildcards(criteria.query()) + "%")
                .addValue("threshold", SIMILARITY_THRESHOLD)
                .addValue("contentTypeIds", SEARCHABLE_CONTENT_TYPE_IDS)
                .addValue("size", criteria.size())
                .addValue("offset", criteria.offset());
    }

    private static String keywordPredicate() {
        return "content_type_id IN (:contentTypeIds) "
                + "  AND (title ILIKE :likePattern ESCAPE '\\' "
                + "   OR similarity(title, :query) > :threshold "
                + "   OR similarity(hangul_jamo(title), hangul_jamo(:query)) > :threshold) ";
    }

    // query에 %/_가 그대로 들어오면 LIKE 와일드카드로 해석된다(SQL 인젝션 위험은 없음 — 바인드
    // 파라미터라 값 자체가 문자열로만 처리됨). 의도치 않게 조건이 느슨해지는 것만 막는다.
    private static String escapeLikeWildcards(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // (ldong_regn_cd = :rcN AND (CAST(:scN AS text) IS NULL OR ldong_signgu_cd = :scN)) OR ...
    // signgu 가 null 이면 그 시/도 전체(03_분석 문서 §4 JOIN 조건과 동일).
    // CAST 는 null 바인드 파라미터의 타입 추론 실패("could not determine data type") 방지(§4-1).
    private static String regionPredicate(List<PlaceSearchCriteria.RegionCode> regionCodes, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < regionCodes.size(); i++) {
            PlaceSearchCriteria.RegionCode regionCode = regionCodes.get(i);
            params.addValue("rc" + i, regionCode.ldongRegnCd());
            params.addValue("sc" + i, regionCode.ldongSignguCd());
            clauses.add("(ldong_regn_cd = :rc" + i
                    + " AND (CAST(:sc" + i + " AS text) IS NULL OR ldong_signgu_cd = :sc" + i + "))");
        }
        return String.join(" OR ", clauses);
    }

    // 선호테마가 있으면 "매치=0 / 비매치=1" 랭크 term 을 ORDER BY 맨 앞에 붙인다(끝에 ", " 포함).
    // level(1/2/3)이 각각 lcls_systm1/2/3 컬럼에 대응(현행 areaBasedList2 의 lclsSystm1/2/3 파라미터 매핑과 동일).
    private static String themeRankOrderTerm(List<PlaceSearchCriteria.ThemeCode> themeCodes, MapSqlParameterSource params) {
        if (themeCodes.isEmpty()) {
            return "";
        }
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < themeCodes.size(); i++) {
            PlaceSearchCriteria.ThemeCode themeCode = themeCodes.get(i);
            params.addValue("lvl" + i, themeCode.level());
            params.addValue("cd" + i, themeCode.code());
            matches.add("(:lvl" + i + " = 1 AND lcls_systm1 = :cd" + i + ")");
            matches.add("(:lvl" + i + " = 2 AND lcls_systm2 = :cd" + i + ")");
            matches.add("(:lvl" + i + " = 3 AND lcls_systm3 = :cd" + i + ")");
        }
        return "CASE WHEN " + String.join(" OR ", matches) + " THEN 0 ELSE 1 END, ";
    }

    private static String textOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return (value == null || value.isBlank()) ? null : value;
    }

    // rs.getDouble()은 SQL NULL 을 0.0 으로 반환 → 좌표 없는 장소가 (0,0)으로 나가는 걸 막으려면 getBigDecimal(§4-6).
    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    /**
     * {@code place_list} 조회 1행 — {@code areaBasedList2} 공용 필드 중 목록/카드뷰에 쓰는 것들.
     * NULL 좌표는 null(0.0 아님), 빈 문자열은 null 로 정규화됨(§4-6).
     */
    public record PlaceRow(
            String contentId,
            String contentTypeId,
            String title,
            String addr1,
            Double mapx,
            Double mapy,
            String firstimage2,
            String cpyrhtDivCd
    ) {
    }
}
