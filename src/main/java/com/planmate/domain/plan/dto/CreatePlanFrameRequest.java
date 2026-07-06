package com.planmate.domain.plan.dto;

import com.planmate.domain.plan.enums.TransportationType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record CreatePlanFrameRequest(
        @NotNull Integer destinationId,
        @NotNull TransportationType transportationType,
        @NotEmpty List<LocalDate> dates,
        @Min(0) int adultCount,
        @Min(0) int childCount
) {
}
