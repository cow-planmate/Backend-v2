package com.planmate.domain.plan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditPlanNameRequest(
        @NotBlank(message = "플랜 이름은 비어 있을 수 없습니다.")
        @Size(max = 100, message = "플랜 이름은 100자를 넘을 수 없습니다.")
        String planName
) {
}
