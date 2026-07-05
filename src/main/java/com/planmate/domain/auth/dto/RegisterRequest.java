package com.planmate.domain.auth.dto;

import com.planmate.domain.user.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterRequest(
        @NotBlank String signupToken,
        @NotBlank @Size(min = 2, max = 20) String nickname,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotNull Gender gender,
        @NotNull LocalDate birthdate
) {
}
