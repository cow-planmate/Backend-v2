package com.planmate.domain.plan.dto;

import java.util.List;
import java.util.UUID;

public record DeleteMultiplePlansResponse(
        List<UUID> deletedPlanIds
) {
}
