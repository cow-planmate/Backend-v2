package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SavePreferredThemeRequest(
        @NotEmpty List<Integer> preferredThemeIds
) {
}
