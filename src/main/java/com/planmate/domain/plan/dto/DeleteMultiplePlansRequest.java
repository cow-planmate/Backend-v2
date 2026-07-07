package com.planmate.domain.plan.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record DeleteMultiplePlansRequest(
        @NotEmpty(message = "삭제할 플랜 ID가 비어 있습니다.")
        List<UUID> planIds
) {
}
