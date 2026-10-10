package com.planmate.domain.destination.dto;

import lombok.Builder;

import java.util.List;

/** 상세 선택용 여행지 트리(시도 → 시군). B_계층_상세.md §5-1. */
@Builder
public record DestinationTreeResponse(
        List<DestinationNodeDto> sidos
) {
}
