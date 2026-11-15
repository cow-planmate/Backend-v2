package com.planmate.domain.destination.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.planmate.domain.destination.entity.Destination;
import lombok.Builder;

import java.util.List;

/**
 * 트리의 노드 하나. 시도 노드는 {@code children}이 항상 배열(광역시·세종은 빈 배열),
 * 시군 노드는 {@code children}이 null이라 응답에서 빠진다(B_계층_상세.md §5-1).
 */
@Builder
public record DestinationNodeDto(
        Integer destinationId,
        String destinationName,
        boolean featured,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<DestinationNodeDto> children
) {
    public static DestinationNodeDto ofSido(Destination sido, List<DestinationNodeDto> children) {
        return DestinationNodeDto.builder()
                .destinationId(sido.getDestinationId())
                .destinationName(sido.getDestinationName())
                .featured(sido.isFeatured())
                .children(List.copyOf(children))
                .build();
    }

    public static DestinationNodeDto ofSigungu(Destination sigungu) {
        return DestinationNodeDto.builder()
                .destinationId(sigungu.getDestinationId())
                .destinationName(sigungu.getDestinationName())
                .featured(sigungu.isFeatured())
                .build();
    }
}
