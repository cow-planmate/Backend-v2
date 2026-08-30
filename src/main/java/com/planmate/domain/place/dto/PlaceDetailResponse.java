package com.planmate.domain.place.dto;

import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.place.repository.PlaceDetailRepository.AttractionIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.FoodIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.ImageRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.InfoItemRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.LodgingIntroRow;
import com.planmate.domain.place.repository.PlaceDetailRepository.RoomRow;
import com.planmate.domain.place.support.TourdataText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;

/**
 * 장소 상세 조회 응답. 카테고리 무관 공통 필드 + {@code attraction}/{@code accommodation}/{@code restaurant}
 * 중 정확히 하나만 non-null(나머지는 null 로 직렬화). 프론트는 {@code category} 로 분기한다.
 * project_ai/04_기능/ai/05_장소상세_API_설계.md §2.
 */
@Builder
public record PlaceDetailResponse(
        String contentId,
        String contentTypeId,
        PlaceCategory category,
        String title,
        String addr1,
        Double latitude,
        Double longitude,
        String imageUrl,
        String thumbnailUrl,
        String copyrightDivCd,
        String overview,
        String homepage,
        List<PlaceImageDto> images,
        AttractionDetail attraction,
        AccommodationDetail accommodation,
        RestaurantDetail restaurant
) {

    public record PlaceImageDto(String originUrl, String smallUrl) {
        public static List<PlaceImageDto> listFrom(List<ImageRow> rows) {
            return rows.stream().map(r -> new PlaceImageDto(r.originUrl(), r.smallUrl())).toList();
        }
    }

    public record PlaceInfoItemDto(String name, String text) {
        private static List<PlaceInfoItemDto> listFrom(List<InfoItemRow> rows) {
            return rows.stream()
                    .map(r -> new PlaceInfoItemDto(TourdataText.normalizeInfoName(r.name()), r.text()))
                    .toList();
        }
    }

    /** 관광지(12) + 문화시설(14) 통합. {@code useFee}/{@code parkingFee}는 14에서만 채워진다(12는 null). */
    @Builder
    public record AttractionDetail(
            String useTime,
            String restDate,
            String parking,
            String infoCenter,
            String useFee,
            String parkingFee,
            List<PlaceInfoItemDto> infoItems
    ) {
        public static AttractionDetail from(AttractionIntroRow row, List<InfoItemRow> infoItemRows) {
            List<PlaceInfoItemDto> infoItems = PlaceInfoItemDto.listFrom(infoItemRows);
            if (row == null) {
                return AttractionDetail.builder().infoItems(infoItems).build();
            }
            return AttractionDetail.builder()
                    .useTime(TourdataText.stripHtml(row.useTime()))
                    .restDate(row.restDate())
                    .parking(TourdataText.stripHtml(row.parking()))
                    .infoCenter(row.infoCenter())
                    .useFee(row.useFee())
                    .parkingFee(row.parkingFee())
                    .infoItems(infoItems)
                    .build();
        }
    }

    @Builder
    public record AccommodationDetail(
            String checkInTime,
            String checkOutTime,
            String roomCount,
            String parking,
            String cooking,
            String subFacility,
            String infoCenter,
            List<String> amenities,
            List<RoomDto> rooms
    ) {
        // 05_ §8 — place_intro_lodging "0"/"1" 플래그 컬럼 → 한글 라벨. 선언 순서 유지.
        private static final Map<String, String> AMENITY_LABELS = new LinkedHashMap<>();

        static {
            AMENITY_LABELS.put("barbecue", "바비큐");
            AMENITY_LABELS.put("campfire", "캠프파이어");
            AMENITY_LABELS.put("sauna", "사우나");
            AMENITY_LABELS.put("fitness", "피트니스 센터");
            AMENITY_LABELS.put("beauty", "뷰티 시설");
            AMENITY_LABELS.put("karaoke", "노래방");
            AMENITY_LABELS.put("sports", "스포츠 시설");
            AMENITY_LABELS.put("bicycle", "자전거 대여");
            AMENITY_LABELS.put("beverage", "음료 시설");
            AMENITY_LABELS.put("foodplace", "식음료장");
            AMENITY_LABELS.put("publicbath", "공용 목욕탕");
            AMENITY_LABELS.put("publicpc", "공용 PC");
            AMENITY_LABELS.put("seminar", "세미나실");
        }

        public static AccommodationDetail from(LodgingIntroRow row, List<RoomRow> roomRows) {
            List<RoomDto> rooms = RoomDto.listFrom(roomRows);
            if (row == null) {
                return AccommodationDetail.builder().amenities(List.of()).rooms(rooms).build();
            }
            List<String> amenities = row.amenityCodes().stream()
                    .map(AMENITY_LABELS::get)
                    .filter(Objects::nonNull)
                    .toList();
            return AccommodationDetail.builder()
                    .checkInTime(row.checkInTime())
                    .checkOutTime(row.checkOutTime())
                    .roomCount(row.roomCount())
                    .parking(row.parking())
                    .cooking(row.cooking())
                    .subFacility(row.subFacility())
                    .infoCenter(row.infoCenter())
                    .amenities(amenities)
                    .rooms(rooms)
                    .build();
        }
    }

    @Builder
    public record RoomDto(
            String roomTitle,
            String roomSize,
            String baseCount,
            String maxCount,
            Long offSeasonWeekdayFee,
            Long offSeasonWeekendFee,
            Long peakSeasonWeekdayFee,
            Long peakSeasonWeekendFee
    ) {
        private static List<RoomDto> listFrom(List<RoomRow> rows) {
            return rows.stream()
                    .map(r -> RoomDto.builder()
                            .roomTitle(r.roomTitle())
                            .roomSize(r.roomSize())
                            .baseCount(r.baseCount())
                            .maxCount(r.maxCount())
                            .offSeasonWeekdayFee(TourdataText.feeOrNull(r.offSeasonWeekdayFee()))
                            .offSeasonWeekendFee(TourdataText.feeOrNull(r.offSeasonWeekendFee()))
                            .peakSeasonWeekdayFee(TourdataText.feeOrNull(r.peakSeasonWeekdayFee()))
                            .peakSeasonWeekendFee(TourdataText.feeOrNull(r.peakSeasonWeekendFee()))
                            .build())
                    .toList();
        }
    }

    public record RestaurantDetail(
            String firstMenu,
            String treatMenu,
            String openTime,
            String restDate,
            String infoCenter,
            String parking
    ) {
        public static RestaurantDetail from(FoodIntroRow row) {
            if (row == null) {
                return new RestaurantDetail(null, null, null, null, null, null);
            }
            return new RestaurantDetail(
                    row.firstMenu(),
                    row.treatMenu(),
                    TourdataText.stripHtml(row.openTime()),
                    row.restDate(),
                    row.infoCenter(),
                    row.parking());
        }
    }
}
