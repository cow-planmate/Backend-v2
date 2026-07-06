package com.planmate.domain.plan.dto;

import java.util.List;

public record GetCompletePlanResponse(
        PlanFrameDetailDto planFrame,
        List<TimetableDetailDto> timetables,
        List<TimetablePlaceBlockDetailDto> placeBlocks
) {
}
