package com.planmate.domain.plan.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateShareRequest(
        @NotNull(message = "공유 여부를 지정해야 합니다.")
        Boolean isShared
) {
}
