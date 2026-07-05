package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

public record ChangeBirthdateRequest(
        @NotNull @Past LocalDate birthdate
) {
}
