package com.planmate.domain.theme.dto;

import com.planmate.domain.theme.entity.PreferredTheme;
import lombok.Builder;

@Builder
public record PreferredThemeDto(
        Integer preferredThemeId,
        String preferredThemeName,
        String category
) {
    public static PreferredThemeDto from(PreferredTheme preferredTheme) {
        return PreferredThemeDto.builder()
                .preferredThemeId(preferredTheme.getPreferredThemeId())
                .preferredThemeName(preferredTheme.getPreferredThemeName())
                .category(preferredTheme.getCategory().name())
                .build();
    }
}
