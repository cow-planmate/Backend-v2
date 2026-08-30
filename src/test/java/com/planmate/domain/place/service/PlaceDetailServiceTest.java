package com.planmate.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.planmate.common.exception.domain.PlaceCacheQueryException;
import com.planmate.common.exception.domain.PlaceDetailNotFoundException;
import com.planmate.domain.place.dto.PlaceDetailResponse;
import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.place.repository.PlaceDetailRepository;
import com.planmate.domain.place.repository.PlaceDetailRepository.AttractionIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.BaseRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.FoodIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.ImageRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.LodgingIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.RoomRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaceDetailServiceTest {

    @Mock
    private PlaceDetailRepository placeDetailRepository;

    private PlaceDetailService placeDetailService;

    @BeforeEach
    void setUp() {
        placeDetailService = new PlaceDetailService(placeDetailRepository);
    }

    private BaseRow base(String contentId, String contentTypeId) {
        return new BaseRow(contentId, contentTypeId, "경복궁", "서울 종로구 사직로 161",
                126.977, 37.579, "https://img/orig", "https://img/thumb", "Type3",
                "<p>경복궁은 1392년 창건된 조선의 법궁이다.</p>", "<a href=\"https://royal.khs.go.kr\">royal</a>");
    }

    // ---------- 성공 + 공통 필드 가공 ----------

    @Test
    @DisplayName("성공 - 관광지(12): attraction 블록만 채워지고 overview HTML 스트립 / homepage URL 추출")
    void 관광지_상세_성공() {
        given(placeDetailRepository.fetchBase("126508")).willReturn(base("126508", "12"));
        given(placeDetailRepository.fetchImages("126508"))
                .willReturn(List.of(new ImageRow("https://img/1", "https://img/1s")));
        given(placeDetailRepository.fetchAttractionIntro("126508", "12"))
                .willReturn(new AttractionIntroRow("09:00~18:00<br>연중무휴", "화요일", "가능<br>요금(무료)",
                        "1330", null, null));
        given(placeDetailRepository.fetchInfoItems("126508")).willReturn(List.of());

        PlaceDetailResponse response = placeDetailService.getPlaceDetail("126508");

        assertThat(response.category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(response.contentTypeId()).isEqualTo("12");
        assertThat(response.overview()).isEqualTo("경복궁은 1392년 창건된 조선의 법궁이다.");
        assertThat(response.homepage()).isEqualTo("https://royal.khs.go.kr");
        assertThat(response.latitude()).isEqualTo(37.579);
        assertThat(response.longitude()).isEqualTo(126.977);
        assertThat(response.images()).hasSize(1);
        assertThat(response.attraction()).isNotNull();
        assertThat(response.attraction().useTime()).isEqualTo("09:00~18:00\n연중무휴");
        assertThat(response.attraction().parking()).isEqualTo("가능\n요금(무료)");
        assertThat(response.accommodation()).isNull();
        assertThat(response.restaurant()).isNull();

        verify(placeDetailRepository, never()).fetchLodgingIntro(any());
        verify(placeDetailRepository, never()).fetchRooms(any());
        verify(placeDetailRepository, never()).fetchFoodIntro(any());
    }

    @Test
    @DisplayName("성공 - 문화시설(14)도 ATTRACTION 카테고리, fetchAttractionIntro에 '14' 전달")
    void 문화시설도_관광지_카테고리() {
        given(placeDetailRepository.fetchBase("1")).willReturn(base("1", "14"));
        given(placeDetailRepository.fetchImages("1")).willReturn(List.of());
        given(placeDetailRepository.fetchAttractionIntro("1", "14"))
                .willReturn(new AttractionIntroRow("상시", "없음", "가능", "1330", "무료", "2,000원"));
        given(placeDetailRepository.fetchInfoItems("1")).willReturn(List.of());

        PlaceDetailResponse response = placeDetailService.getPlaceDetail("1");

        assertThat(response.category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(response.attraction().useFee()).isEqualTo("무료");
        assertThat(response.attraction().parkingFee()).isEqualTo("2,000원");
        verify(placeDetailRepository).fetchAttractionIntro("1", "14");
    }

    @Test
    @DisplayName("성공 - 숙박(32): accommodation 블록만, 부대시설 코드→한글 라벨, 요금 '0'→null")
    void 숙박_상세_성공() {
        given(placeDetailRepository.fetchBase("32001")).willReturn(base("32001", "32"));
        given(placeDetailRepository.fetchImages("32001")).willReturn(List.of());
        given(placeDetailRepository.fetchLodgingIntro("32001"))
                .willReturn(new LodgingIntroRow("15:00", "11:00", "50", "가능", "가능", "수영장",
                        "064-000-0000", List.of("sauna", "publicbath")));
        given(placeDetailRepository.fetchRooms("32001"))
                .willReturn(List.of(new RoomRow("스탠다드", "23", "2", "3", "80000", "0", "120000", "150000")));

        PlaceDetailResponse response = placeDetailService.getPlaceDetail("32001");

        assertThat(response.category()).isEqualTo(PlaceCategory.ACCOMMODATION);
        assertThat(response.attraction()).isNull();
        assertThat(response.restaurant()).isNull();
        assertThat(response.accommodation().amenities()).containsExactly("사우나", "공용 목욕탕");
        assertThat(response.accommodation().rooms()).hasSize(1);
        assertThat(response.accommodation().rooms().get(0).offSeasonWeekdayFee()).isEqualTo(80000L);
        assertThat(response.accommodation().rooms().get(0).offSeasonWeekendFee()).isNull(); // "0" → null
        assertThat(response.accommodation().rooms().get(0).peakSeasonWeekdayFee()).isEqualTo(120000L);

        verify(placeDetailRepository, never()).fetchAttractionIntro(any(), any());
        verify(placeDetailRepository, never()).fetchInfoItems(any());
        verify(placeDetailRepository, never()).fetchFoodIntro(any());
    }

    @Test
    @DisplayName("성공 - 음식점(39): restaurant 블록만, openTime HTML 스트립")
    void 음식점_상세_성공() {
        given(placeDetailRepository.fetchBase("39001")).willReturn(base("39001", "39"));
        given(placeDetailRepository.fetchImages("39001")).willReturn(List.of());
        given(placeDetailRepository.fetchFoodIntro("39001"))
                .willReturn(new FoodIntroRow("충무김밥", "김밥/어묵/등", "11:00~21:00<br>브레이크타임 15:00~17:00",
                        "연중무휴", "055-000-0000", "불가"));

        PlaceDetailResponse response = placeDetailService.getPlaceDetail("39001");

        assertThat(response.category()).isEqualTo(PlaceCategory.RESTAURANT);
        assertThat(response.restaurant().firstMenu()).isEqualTo("충무김밥");
        assertThat(response.restaurant().openTime()).isEqualTo("11:00~21:00\n브레이크타임 15:00~17:00");
        assertThat(response.attraction()).isNull();
        assertThat(response.accommodation()).isNull();

        verify(placeDetailRepository, never()).fetchInfoItems(any());
        verify(placeDetailRepository, never()).fetchRooms(any());
    }

    @Test
    @DisplayName("성공 - intro 행이 없어도(fetch*Intro null) 블록은 채워지고 필드만 null")
    void intro_없으면_빈_블록() {
        given(placeDetailRepository.fetchBase("39002")).willReturn(base("39002", "39"));
        given(placeDetailRepository.fetchImages("39002")).willReturn(List.of());
        given(placeDetailRepository.fetchFoodIntro("39002")).willReturn(null);

        PlaceDetailResponse response = placeDetailService.getPlaceDetail("39002");

        assertThat(response.restaurant()).isNotNull();
        assertThat(response.restaurant().firstMenu()).isNull();
        assertThat(response.restaurant().openTime()).isNull();
    }

    // ---------- 실패 케이스 (05_ §10) ----------

    @Test
    @DisplayName("실패 - content_id가 place_list에 없으면 PlaceDetailNotFoundException(404)")
    void 없는_contentId면_404() {
        given(placeDetailRepository.fetchBase("nope")).willReturn(null);

        assertThatThrownBy(() -> placeDetailService.getPlaceDetail("nope"))
                .isInstanceOf(PlaceDetailNotFoundException.class);
        verify(placeDetailRepository, never()).fetchImages(any());
    }

    @Test
    @DisplayName("실패 - 상세 미지원 타입(여행코스 25)이면 PlaceDetailNotFoundException(404)")
    void 미지원_카테고리면_404() {
        given(placeDetailRepository.fetchBase("25001")).willReturn(base("25001", "25"));

        assertThatThrownBy(() -> placeDetailService.getPlaceDetail("25001"))
                .isInstanceOf(PlaceDetailNotFoundException.class);
        verify(placeDetailRepository, never()).fetchImages(any());
    }

    @Test
    @DisplayName("실패 - 축제(15)도 상세 미지원 → 404")
    void 축제도_404() {
        given(placeDetailRepository.fetchBase("15001")).willReturn(base("15001", "15"));

        assertThatThrownBy(() -> placeDetailService.getPlaceDetail("15001"))
                .isInstanceOf(PlaceDetailNotFoundException.class);
    }

    @Test
    @DisplayName("실패 - 캐시 DB 오류(PlaceCacheQueryException)는 그대로 전파(503)")
    void 캐시_DB_오류_전파() {
        given(placeDetailRepository.fetchBase("126508"))
                .willThrow(new PlaceCacheQueryException(new RuntimeException("db down")));

        assertThatThrownBy(() -> placeDetailService.getPlaceDetail("126508"))
                .isInstanceOf(PlaceCacheQueryException.class);
    }

    @Test
    @DisplayName("실패 - 1:N 조회 단계에서 캐시 DB 오류가 나도 그대로 전파")
    void 이미지_조회_중_캐시_오류_전파() {
        given(placeDetailRepository.fetchBase("126508")).willReturn(base("126508", "12"));
        given(placeDetailRepository.fetchImages("126508"))
                .willThrow(new PlaceCacheQueryException(new RuntimeException("db down")));

        assertThatThrownBy(() -> placeDetailService.getPlaceDetail("126508"))
                .isInstanceOf(PlaceCacheQueryException.class);
    }

    @Test
    @DisplayName("요청받은 contentId를 그대로 fetchBase에 넘긴다(파라미터 위조 없음)")
    void contentId_그대로_전달() {
        given(placeDetailRepository.fetchBase("abc-123")).willReturn(base("abc-123", "12"));
        given(placeDetailRepository.fetchImages("abc-123")).willReturn(List.of());
        given(placeDetailRepository.fetchAttractionIntro(eq("abc-123"), any())).willReturn(null);
        given(placeDetailRepository.fetchInfoItems("abc-123")).willReturn(List.of());

        placeDetailService.getPlaceDetail("abc-123");

        verify(placeDetailRepository).fetchBase("abc-123");
    }
}
