package com.planmate.domain.auth.dto;

import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EmailVerificationSendRequest(
        @NotBlank @Email String email,
        @NotNull EmailVerificationPurpose purpose
) {
}
