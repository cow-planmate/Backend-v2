package com.planmate.domain.place.repository;

import com.planmate.common.exception.domain.PlaceCacheQueryException;
import com.planmate.domain.place.dto.PlaceSearchCriteria;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
