package com.planmate.domain.plan.dto;

import com.planmate.domain.plan.enums.TransportationType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PlanFrameDto(
        @NotNull Integer destinationId,
        @NotNull TransportationType transportationType,
        @Min(0) int adultCount,
        @Min(0) int childCount
) {
}
