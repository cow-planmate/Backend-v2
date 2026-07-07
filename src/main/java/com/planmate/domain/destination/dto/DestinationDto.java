package com.planmate.domain.destination.dto;

import com.planmate.domain.destination.entity.Destination;
import lombok.Builder;

@Builder
public record DestinationDto(
        Integer destinationId,
        String destinationName
) {
    public static DestinationDto from(Destination destination) {
        return DestinationDto.builder()
                .destinationId(destination.getDestinationId())
                .destinationName(destination.getDestinationName())
                .build();
    }
}
