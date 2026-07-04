package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SavePreferredThemeRequest(
        @NotEmpty List<@NotNull Integer> preferredThemeIds
) {
}
