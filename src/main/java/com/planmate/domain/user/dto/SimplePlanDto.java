package com.planmate.domain.user.dto;

import com.planmate.domain.plan.entity.Plan;
import lombok.Builder;

import java.util.UUID;

@Builder
public record SimplePlanDto(
        UUID planId,
        String planName
) {
    public static SimplePlanDto from(Plan plan) {
        return SimplePlanDto.builder()
                .planId(plan.getPlanId())
                .planName(plan.getPlanName())
                .build();
    }
}
