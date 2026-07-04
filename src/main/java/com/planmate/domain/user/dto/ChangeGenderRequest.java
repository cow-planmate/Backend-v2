package com.planmate.domain.user.dto;

import com.planmate.domain.user.enums.Gender;
import jakarta.validation.constraints.NotNull;

public record ChangeGenderRequest(
        @NotNull Gender gender
) {
}
