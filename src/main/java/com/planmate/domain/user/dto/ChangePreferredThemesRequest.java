package com.planmate.domain.user.dto;

import com.planmate.domain.theme.enums.PreferredThemeCategory;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record ChangePreferredThemesRequest(
        @NotEmpty Map<PreferredThemeCategory, @NotNull List<@NotNull Integer>> themeUpdates
) {
}
