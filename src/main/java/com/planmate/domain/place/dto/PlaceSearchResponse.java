package com.planmate.domain.place.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record PlaceSearchResponse(
        List<PlaceSummaryDto> places,
        long totalCount,
        int page,
        int size,
        boolean hasNext
) {
}
