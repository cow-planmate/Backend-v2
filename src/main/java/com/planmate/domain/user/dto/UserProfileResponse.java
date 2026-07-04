package com.planmate.domain.user.dto;

import com.planmate.domain.theme.dto.PreferredThemeDto;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder
public record UserProfileResponse(
        UUID userId,
        String email,
        String nickname,
        LocalDate birthdate,
        String gender,
        boolean isSocialLogin,
        List<SimplePlanDto> myPlans,
        List<SimplePlanDto> editablePlans,
        List<PreferredThemeDto> preferredThemes
) {
}
