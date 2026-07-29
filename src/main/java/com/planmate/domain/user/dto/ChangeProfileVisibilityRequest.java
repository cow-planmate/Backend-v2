package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotNull;

public record ChangeProfileVisibilityRequest(
        @NotNull Boolean profilePublic
) {
}
