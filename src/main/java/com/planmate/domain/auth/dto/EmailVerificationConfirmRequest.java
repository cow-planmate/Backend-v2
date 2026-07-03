package com.planmate.domain.auth.dto;

import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EmailVerificationConfirmRequest(
        @NotBlank @Email String email,
        @NotNull EmailVerificationPurpose purpose,
        @NotNull @Min(100000) @Max(999999) Integer verificationCode
) {
}
