package com.planmate.domain.place.dto;

import com.planmate.common.externalAPI.GoogleSearchClient.GoogleTextSearchItem;
import lombok.Builder;

// 추천(PlaceSummaryDto)과 노출 필드를 맞췄다 — rating/userRatingsTotal/priceLevel/types 등
// Google 전용 필드는 응답에도 없음(확정, 07_키워드검색_API_상세설계.md §5-2 (b)).
@Builder
public record PlaceTextSearchResultDto(
        String placeId,
        String name,
        String address,
        String thumbnailUrl,
        Double latitude,
        Double longitude
) {
    public static PlaceTextSearchResultDto from(GoogleTextSearchItem item, String thumbnailUrl) {
        return PlaceTextSearchResultDto.builder()
                .placeId(item.placeId())
                .name(item.name())
                .address(item.address())
                .thumbnailUrl(thumbnailUrl)
                .latitude(item.lat())
                .longitude(item.lng())
                .build();
    }
}
