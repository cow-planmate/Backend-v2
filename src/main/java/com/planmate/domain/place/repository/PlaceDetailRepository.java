package com.planmate.domain.place.repository;

import com.planmate.common.exception.domain.PlaceCacheQueryException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 장소 상세 = tourdata 캐시 DB(읽기 전용) 앱조인 조회. {@code content_id} 로 1:1 테이블은 조인,
 * 1:N 테이블(이미지/반복정보/객실)은 별도 {@code SELECT}. 카테고리별 2~4쿼리, 전부 PK/인덱스 seek.
 * project_ai/04_기능/ai/05_장소상세_API_설계.md §3·§5.
 */
@Repository
@RequiredArgsConstructor
public class PlaceDetailRepository {

    @Qualifier("tourdataJdbcTemplate")
    private final NamedParameterJdbcTemplate jdbc;

    // place_intro_lodging 부대시설 플래그 13종. "1"이면 보유 — 05_ §8. (한글 라벨은 응답 DTO 소관)
    private static final List<String> AMENITY_COLUMNS = List.of(
            "barbecue", "campfire", "sauna", "fitness", "beauty", "karaoke", "sports",
            "bicycle", "beverage", "foodplace", "publicbath", "publicpc", "seminar");

    // ── ① place_list ⟕ place_common ────────────────────────────────────────────

    private static final RowMapper<BaseRow> BASE_MAPPER = (rs, n) -> new BaseRow(
            textOrNull(rs, "content_id"),
            textOrNull(rs, "content_type_id"),
            textOrNull(rs, "title"),
            textOrNull(rs, "addr1"),
            doubleOrNull(rs, "mapx"),
            doubleOrNull(rs, "mapy"),
            textOrNull(rs, "firstimage"),
            textOrNull(rs, "firstimage2"),
            textOrNull(rs, "cpyrht_div_cd"),
            textOrNull(rs, "overview"),
            textOrNull(rs, "homepage"));

    private static final String BASE_SQL = """
            SELECT l.content_id, l.content_type_id, l.title, l.addr1, l.mapx, l.mapy,
                   l.firstimage, l.firstimage2, l.cpyrht_div_cd,
                   c.overview, c.homepage
            FROM place_list l
            LEFT JOIN place_common c USING (content_id)
            WHERE l.content_id = :contentId
            """;

    /** {@code content_id} 로 1건. 없으면 null → Service 가 {@code PlaceDetailNotFoundException}(404). */
    public BaseRow fetchBase(String contentId) {
        return queryCache(() -> jdbc.query(BASE_SQL, params(contentId), BASE_MAPPER)
                .stream().findFirst().orElse(null));
    }

    // ── ② place_intro_* (타입별 1건) — alias 를 응답 필드에 맞춰 통일 ──────────────

    private static final RowMapper<AttractionIntroRow> ATTRACTION_INTRO_MAPPER = (rs, n) -> new AttractionIntroRow(
            textOrNull(rs, "use_time"),
            textOrNull(rs, "rest_date"),
            textOrNull(rs, "parking"),
            textOrNull(rs, "info_center"),
            textOrNull(rs, "use_fee"),
            textOrNull(rs, "parking_fee"));

    private static final String ATTRACTION_12_SQL = """
            SELECT usetime AS use_time, restdate AS rest_date, parking,
                   infocenter AS info_center, NULL AS use_fee, NULL AS parking_fee
            FROM place_intro_attraction WHERE content_id = :contentId
            """;

    private static final String CULTURE_14_SQL = """
            SELECT usetimeculture AS use_time, restdateculture AS rest_date, parkingculture AS parking,
                   infocenterculture AS info_center, usefee AS use_fee, parkingfee AS parking_fee
            FROM place_intro_culture WHERE content_id = :contentId
            """;

    /** {@code contentTypeId}(place_list 실제 값)로 12=관광지 / 14=문화시설 테이블 선택. 없으면 null. */
    public AttractionIntroRow fetchAttractionIntro(String contentId, String contentTypeId) {
        String sql = "14".equals(contentTypeId) ? CULTURE_14_SQL : ATTRACTION_12_SQL;
        return queryCache(() -> jdbc.query(sql, params(contentId), ATTRACTION_INTRO_MAPPER)
                .stream().findFirst().orElse(null));
    }

    private static final RowMapper<LodgingIntroRow> LODGING_INTRO_MAPPER = (rs, n) -> {
        List<String> amenityCodes = new ArrayList<>();
        for (String column : AMENITY_COLUMNS) {
            if ("1".equals(rs.getString(column))) {
                amenityCodes.add(column);
            }
        }
        return new LodgingIntroRow(
                textOrNull(rs, "check_in_time"),
                textOrNull(rs, "check_out_time"),
                textOrNull(rs, "room_count"),
                textOrNull(rs, "parking"),
                textOrNull(rs, "cooking"),
                textOrNull(rs, "sub_facility"),
                textOrNull(rs, "info_center"),
                amenityCodes);
    };

    private static final String LODGING_INTRO_SQL = """
            SELECT checkintime AS check_in_time, checkouttime AS check_out_time, roomcount AS room_count,
                   parkinglodging AS parking, chkcooking AS cooking, subfacility AS sub_facility,
                   infocenterlodging AS info_center,
                   barbecue, campfire, sauna, fitness, beauty, karaoke, sports,
                   bicycle, beverage, foodplace, publicbath, publicpc, seminar
            FROM place_intro_lodging WHERE content_id = :contentId
            """;

    public LodgingIntroRow fetchLodgingIntro(String contentId) {
        return queryCache(() -> jdbc.query(LODGING_INTRO_SQL, params(contentId), LODGING_INTRO_MAPPER)
                .stream().findFirst().orElse(null));
    }

    private static final RowMapper<FoodIntroRow> FOOD_INTRO_MAPPER = (rs, n) -> new FoodIntroRow(
            textOrNull(rs, "first_menu"),
            textOrNull(rs, "treat_menu"),
            textOrNull(rs, "open_time"),
            textOrNull(rs, "rest_date"),
            textOrNull(rs, "info_center"),
            textOrNull(rs, "parking"));

    private static final String FOOD_INTRO_SQL = """
            SELECT firstmenu AS first_menu, treatmenu AS treat_menu, opentimefood AS open_time,
                   restdatefood AS rest_date, infocenterfood AS info_center, parkingfood AS parking
            FROM place_intro_food WHERE content_id = :contentId
            """;

    public FoodIntroRow fetchFoodIntro(String contentId) {
        return queryCache(() -> jdbc.query(FOOD_INTRO_SQL, params(contentId), FOOD_INTRO_MAPPER)
                .stream().findFirst().orElse(null));
    }

    // ── ③④⑤ 1:N — id(적재 순서 = API 반환 순서) 정렬 ──────────────────────────────

    private static final RowMapper<ImageRow> IMAGE_MAPPER = (rs, n) -> new ImageRow(
            textOrNull(rs, "originimgurl"), textOrNull(rs, "smallimageurl"));

    /** 장소 이미지 최대 10장(05_ §2·§13). 없으면 빈 리스트. */
    public List<ImageRow> fetchImages(String contentId) {
        return queryCache(() -> jdbc.query(
                "SELECT originimgurl, smallimageurl FROM place_image "
                        + "WHERE content_id = :contentId ORDER BY id LIMIT 10",
                params(contentId), IMAGE_MAPPER));
    }

    private static final RowMapper<InfoItemRow> INFO_ITEM_MAPPER = (rs, n) -> new InfoItemRow(
            textOrNull(rs, "infoname"), textOrNull(rs, "infotext"));

    /** 관광지(12·14) 반복정보. 음식점·숙박은 호출 안 함(05_ §3). 없으면 빈 리스트. */
    public List<InfoItemRow> fetchInfoItems(String contentId) {
        return queryCache(() -> jdbc.query(
                "SELECT infoname, infotext FROM place_info_common "
                        + "WHERE content_id = :contentId ORDER BY id",
                params(contentId), INFO_ITEM_MAPPER));
    }

    private static final RowMapper<RoomRow> ROOM_MAPPER = (rs, n) -> new RoomRow(
            textOrNull(rs, "roomtitle"),
            textOrNull(rs, "roomsize1"),
            textOrNull(rs, "roombasecount"),
            textOrNull(rs, "roommaxcount"),
            textOrNull(rs, "roomoffseasonminfee1"),
            textOrNull(rs, "roomoffseasonminfee2"),
            textOrNull(rs, "roompeakseasonminfee1"),
            textOrNull(rs, "roompeakseasonminfee2"));

    /** 숙박 객실. 요금 파싱("0"→null)은 응답 DTO 소관. 없으면 빈 리스트. */
    public List<RoomRow> fetchRooms(String contentId) {
        return queryCache(() -> jdbc.query(
                """
                SELECT roomtitle, roomsize1, roombasecount, roommaxcount,
                       roomoffseasonminfee1, roomoffseasonminfee2,
                       roompeakseasonminfee1, roompeakseasonminfee2
                FROM place_info_room WHERE content_id = :contentId ORDER BY id
                """,
                params(contentId), ROOM_MAPPER));
    }

    // ── 공용 ─────────────────────────────────────────────────────────────────────

    private static MapSqlParameterSource params(String contentId) {
        return new MapSqlParameterSource("contentId", contentId);
    }

    // 캐시 DB 장애(DataAccessException)를 도메인 예외로 래핑 → 503(PLACE_005). 03_ §4-8과 동일.
    private static <T> T queryCache(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new PlaceCacheQueryException(e);
        }
    }

    private static String textOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return (value == null || value.isBlank()) ? null : value;
    }

    // rs.getDouble()은 SQL NULL 을 0.0 으로 반환 → 좌표 없는 장소가 (0,0)으로 나가는 걸 막는다(05_ §7-4).
    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    // ── 조회 결과 행(raw) — 값 가공은 응답 DTO factory / TourdataText 소관(05_ §6) ──

    public record BaseRow(
            String contentId,
            String contentTypeId,
            String title,
            String addr1,
            Double mapx,
            Double mapy,
            String firstImage,
            String thumbnail,
            String copyrightDivCd,
            String overview,
            String homepage
    ) {
    }

    public record AttractionIntroRow(
            String useTime, String restDate, String parking, String infoCenter,
            String useFee, String parkingFee
    ) {
    }

    public record LodgingIntroRow(
            String checkInTime, String checkOutTime, String roomCount, String parking,
            String cooking, String subFacility, String infoCenter,
            /** 값이 {@code "1"}인 부대시설 플래그 컬럼명(선언 순서). 한글 라벨 변환은 응답 DTO. */
            List<String> amenityCodes
    ) {
    }

    public record FoodIntroRow(
            String firstMenu, String treatMenu, String openTime, String restDate,
            String infoCenter, String parking
    ) {
    }

    public record ImageRow(String originUrl, String smallUrl) {
    }

    public record InfoItemRow(String name, String text) {
    }

    public record RoomRow(
            String roomTitle, String roomSize, String baseCount, String maxCount,
            String offSeasonWeekdayFee, String offSeasonWeekendFee,
            String peakSeasonWeekdayFee, String peakSeasonWeekendFee
    ) {
    }
}
