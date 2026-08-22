package com.planmate.domain.place.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record PlaceTextSearchResponse(
        List<PlaceTextSearchResultDto> places,
        // nullable — Google이 더 줄 페이지가 없으면 null
        String nextPageToken
) {
}
