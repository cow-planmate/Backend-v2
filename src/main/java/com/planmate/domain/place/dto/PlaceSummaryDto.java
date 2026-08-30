package com.planmate.domain.place.dto;

import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.place.repository.PlaceCacheRepository.PlaceRow;
import lombok.Builder;

@Builder
public record PlaceSummaryDto(
        String contentId,
        String contentTypeId,
        PlaceCategory category,
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
    // category는 요청 파라미터를 그대로 복사하지 않고 contentTypeId에서 역유도 — ATTRACTION이 12/14를 섞어서 반환하므로
    // 실제 데이터 기준으로 계산해야 항상 정확함(향후 "장소를 일정에 저장"할 때 blockCategory 매핑에도 바로 사용 가능).
    // null 좌표·빈 문자열 정규화는 PlaceCacheRepository RowMapper 가 이미 처리(§4-6) — 여기선 그대로 매핑만.
    public static PlaceSummaryDto from(PlaceRow row) {
        return PlaceSummaryDto.builder()
                .contentId(row.contentId())
                .contentTypeId(row.contentTypeId())
                .category(PlaceCategory.fromContentTypeId(row.contentTypeId()))
                .title(row.title())
                .addr1(row.addr1())
                .thumbnailUrl(row.firstimage2())
                .copyrightDivCd(row.cpyrhtDivCd())
                .latitude(row.mapy())
                .longitude(row.mapx())
                .build();
    }
}
