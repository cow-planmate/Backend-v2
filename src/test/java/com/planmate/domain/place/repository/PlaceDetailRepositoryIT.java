package com.planmate.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.planmate.domain.place.repository.PlaceDetailRepository.AttractionIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.BaseRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.FoodIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.ImageRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.InfoItemRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.LodgingIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.RoomRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code PlaceDetailRepository} 앱조인 SQL 검증 — 실제 tourdata 캐시(localhost:5433).
 * CI 러너엔 컨테이너가 없어 {@code @Tag("external")}로 기본 {@code test}에서 제외.
 * 로컬: {@code docker compose -f docker-compose.local.yml up -d} 후 {@code ./gradlew externalTest}.
 *
 * <p>단언은 정확한 건수가 아니라 범위/불변식으로 — 최신화 잡이 로컬 데이터를 바꿀 수 있으므로.
 * 05_장소상세_API_설계.md §13-1.
 */
@Tag("external")
@SpringBootTest
class PlaceDetailRepositoryIT {

    @Autowired
    private PlaceDetailRepository repository;

    @Autowired
    @Qualifier("tourdataJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    private String oneContentId(String where) {
        return jdbc.queryForList("SELECT content_id FROM place_list WHERE " + where + " LIMIT 1",
                Map.of(), String.class).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("fetchBase - place_list⟕place_common 1건, 좌표는 Double, 없는 id는 null")
    void fetchBase_기본() {
        String contentId = oneContentId("content_type_id = '12'");
        assumeTrue(contentId != null);

        BaseRow base = repository.fetchBase(contentId);

        assertThat(base).isNotNull();
        assertThat(base.contentId()).isEqualTo(contentId);
        assertThat(base.contentTypeId()).isEqualTo("12");
        assertThat(base.title()).isNotBlank();

        assertThat(repository.fetchBase("no-such-content-id-xyz")).isNull();
    }

    @Test
    @DisplayName("fetchBase - 좌표가 NULL인 행은 mapx/mapy가 null (0.0 아님)")
    void fetchBase_좌표_NULL은_null() {
        String contentId = oneContentId("mapx IS NULL");
        assumeTrue(contentId != null, "좌표 NULL 행이 없으면 스킵");

        BaseRow base = repository.fetchBase(contentId);

        assertThat(base.mapx()).isNull();
        assertThat(base.mapy()).isNull();
    }

    @Test
    @DisplayName("fetchAttractionIntro - 12는 place_intro_attraction, use_fee/parking_fee는 null")
    void fetchAttractionIntro_12() {
        String contentId = oneContentId(
                "content_type_id = '12' AND content_id IN (SELECT content_id FROM place_intro_attraction)");
        assumeTrue(contentId != null);

        AttractionIntroRow row = repository.fetchAttractionIntro(contentId, "12");

        assertThat(row).isNotNull();
        assertThat(row.useFee()).isNull();
        assertThat(row.parkingFee()).isNull();
    }

    @Test
    @DisplayName("fetchAttractionIntro - 14는 place_intro_culture, usefee/parkingfee가 use_fee/parking_fee로 매핑")
    void fetchAttractionIntro_14_alias통일() {
        String contentId = oneContentId(
                "content_type_id = '14' AND content_id IN (SELECT content_id FROM place_intro_culture WHERE usefee IS NOT NULL AND usefee <> '')");
        assumeTrue(contentId != null);

        AttractionIntroRow row = repository.fetchAttractionIntro(contentId, "14");

        assertThat(row).isNotNull();
        // 14 전용 필드가 실제로 채워져 매핑되는지(alias 통일 확인)
        assertThat(row.useFee()).isNotBlank();
    }

    @Test
    @DisplayName("fetchLodgingIntro - 숙박 운영정보 + 부대시설 코드는 값이 '1'인 컬럼만, 선언 순서")
    void fetchLodgingIntro_부대시설_코드() {
        String contentId = oneContentId(
                "content_type_id = '32' AND content_id IN (SELECT content_id FROM place_intro_lodging)");
        assumeTrue(contentId != null);

        LodgingIntroRow row = repository.fetchLodgingIntro(contentId);

        assertThat(row).isNotNull();
        assertThat(row.amenityCodes()).isSubsetOf(
                "barbecue", "campfire", "sauna", "fitness", "beauty", "karaoke", "sports",
                "bicycle", "beverage", "foodplace", "publicbath", "publicpc", "seminar");
    }

    @Test
    @DisplayName("fetchFoodIntro - 음식점 컬럼이 응답 필드명으로 매핑")
    void fetchFoodIntro_매핑() {
        String contentId = oneContentId(
                "content_type_id = '39' AND content_id IN (SELECT content_id FROM place_intro_food WHERE firstmenu IS NOT NULL AND firstmenu <> '')");
        assumeTrue(contentId != null);

        FoodIntroRow row = repository.fetchFoodIntro(contentId);

        assertThat(row).isNotNull();
        assertThat(row.firstMenu()).isNotBlank();
    }

    @Test
    @DisplayName("fetchImages - 상한 10장 이하로만 나온다(현행 데이터는 장소당 최대 10장), 없으면 빈 리스트")
    void fetchImages_10장_상한() {
        // 현행 tourdata 는 장소당 이미지가 최대 10장이라(02_적재_데이터_검증.md 재측정) 10장 초과 절단은
        // 데이터로 직접 재현 불가 — LIMIT 10 이 상한을 넘기지 않는지(<=10)만 확인한다.
        String maxImages = jdbc.queryForList(
                "SELECT content_id FROM place_image GROUP BY content_id ORDER BY count(*) DESC LIMIT 1",
                Map.of(), String.class).stream().findFirst().orElse(null);
        assumeTrue(maxImages != null, "이미지 보유 장소가 없으면 스킵");

        List<ImageRow> images = repository.fetchImages(maxImages);

        assertThat(images).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(images).allSatisfy(i -> assertThat(i.originUrl()).isNotBlank());

        assertThat(repository.fetchImages("no-such-content-id-xyz")).isEmpty();
    }

    @Test
    @DisplayName("fetchInfoItems - 관광지 반복정보, 없으면 빈 리스트")
    void fetchInfoItems() {
        String contentId = jdbc.queryForList(
                "SELECT content_id FROM place_info_common WHERE content_id IN "
                        + "(SELECT content_id FROM place_list WHERE content_type_id IN ('12','14')) LIMIT 1",
                Map.of(), String.class).stream().findFirst().orElse(null);
        assumeTrue(contentId != null);

        List<InfoItemRow> items = repository.fetchInfoItems(contentId);

        assertThat(items).isNotEmpty();
        assertThat(repository.fetchInfoItems("no-such-content-id-xyz")).isEmpty();
    }

    @Test
    @DisplayName("fetchRooms - 숙박 객실 raw 행(요금은 문자열 그대로), 없으면 빈 리스트")
    void fetchRooms() {
        String contentId = jdbc.queryForList(
                "SELECT content_id FROM place_info_room LIMIT 1", Map.of(), String.class)
                .stream().findFirst().orElse(null);
        assumeTrue(contentId != null);

        List<RoomRow> rooms = repository.fetchRooms(contentId);

        assertThat(rooms).isNotEmpty();
        assertThat(rooms).allSatisfy(r -> assertThat(r.roomTitle()).isNotNull());

        assertThat(repository.fetchRooms("no-such-content-id-xyz")).isEmpty();
    }
}
