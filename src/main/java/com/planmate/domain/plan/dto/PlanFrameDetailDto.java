package com.planmate.domain.plan.dto;

import com.planmate.domain.plan.entity.Plan;

import java.util.UUID;

public record PlanFrameDetailDto(
        UUID planId,
        String planName,
        Integer destinationId,
        String destinationName,
        int adultCount,
        int childCount
) {
    public static PlanFrameDetailDto from(Plan plan) {
        return new PlanFrameDetailDto(
                plan.getPlanId(),
                plan.getPlanName(),
                plan.getDestination().getDestinationId(),
                plan.getDestination().getDestinationName(),
                plan.getAdultCount(),
                plan.getChildCount()
        );
    }
}
