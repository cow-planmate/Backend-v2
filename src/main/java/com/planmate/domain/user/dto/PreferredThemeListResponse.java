package com.planmate.domain.user.dto;

import com.planmate.domain.theme.dto.PreferredThemeDto;
import lombok.Builder;

import java.util.List;

@Builder
public record PreferredThemeListResponse(
        List<PreferredThemeDto> preferredThemes
) {
}
