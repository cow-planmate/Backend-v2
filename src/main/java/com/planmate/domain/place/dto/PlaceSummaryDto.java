package com.planmate.domain.place.dto;

import com.planmate.common.externalAPI.TourApiClient.AreaBasedListItem;
import lombok.Builder;

@Builder
public record PlaceSummaryDto(
        String contentId,
        String contentTypeId,
        String title,
        String addr1,
        String thumbnailUrl,
        String copyrightDivCd,
        Double latitude,
        Double longitude
) {
    // 응답엔 포함하되 프론트 UI엔 값을 화면 표시만 안 함 — 지도 렌더링, 장소를 일정에 저장할 때 좌표로 사용.
    // addr2/ldongRegnCd 제외 이유: addr2는 채움률 15%대에 형식도 제각각(법정동/층수/호실 등 뒤섞임, 2026-07-21 실측),
    // ldongRegnCd는 한 응답 안에서 destinationId 기준으로 항상 동일한 값이라 아이템별로 실어 보낼 이유가 없음.
    public static PlaceSummaryDto from(AreaBasedListItem item) {
        return PlaceSummaryDto.builder()
                .contentId(item.contentId())
                .contentTypeId(item.contentTypeId())
                .title(item.title())
                .addr1(item.addr1())
                .thumbnailUrl(item.thumbnailUrl())
                .copyrightDivCd(item.copyrightDivCd())
                .latitude(item.mapY())
                .longitude(item.mapX())
                .build();
    }
}
