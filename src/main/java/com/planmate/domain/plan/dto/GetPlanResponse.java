package com.planmate.domain.plan.dto;

import java.util.List;

public record GetPlanResponse(
        PlanFrameDetailDto planFrame,
        List<TimetableDetailDto> timetables,
        List<TimetablePlaceBlockDetailDto> placeBlocks
) {
}
