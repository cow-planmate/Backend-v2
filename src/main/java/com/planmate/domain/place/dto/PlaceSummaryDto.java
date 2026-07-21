package com.planmate.domain.place.dto;

import com.planmate.common.externalAPI.TourApiClient.AreaBasedListItem;
import lombok.Builder;

@Builder
public record PlaceSummaryDto(
        String contentId,
        String contentTypeId,
        String title,
        String addr1,
        String addr2,
        String thumbnailUrl,
        String copyrightDivCd,
        String ldongRegnCd,
        Double latitude,
        Double longitude
) {
    // 응답엔 포함하되 프론트 UI엔 값을 화면 표시만 안 함 — 지도 렌더링, 장소를 일정에 저장할 때 좌표로 사용.
    public static PlaceSummaryDto from(AreaBasedListItem item) {
        return PlaceSummaryDto.builder()
                .contentId(item.contentId())
                .contentTypeId(item.contentTypeId())
                .title(item.title())
                .addr1(item.addr1())
                .addr2(item.addr2())
                .thumbnailUrl(item.thumbnailUrl())
                .copyrightDivCd(item.copyrightDivCd())
                .ldongRegnCd(item.ldongRegnCd())
                .latitude(item.mapY())
                .longitude(item.mapX())
                .build();
    }
}
