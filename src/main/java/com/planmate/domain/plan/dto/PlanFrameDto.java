package com.planmate.domain.plan.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PlanFrameDto(
        @NotNull Integer destinationId,
        @Min(0) int adultCount,
        @Min(0) int childCount
) {
}
