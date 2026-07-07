package com.planmate.domain.destination.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record DestinationListResponse(
        List<DestinationDto> destinations
) {
}
