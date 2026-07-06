package com.planmate.domain.plan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreatePlanRequest(
        @NotNull @Valid PlanFrameDto planFrame,
        @Valid List<TimetableDto> timetables,
        @Valid List<TimetablePlaceBlockDto> timetablePlaceBlocks
) {
}
