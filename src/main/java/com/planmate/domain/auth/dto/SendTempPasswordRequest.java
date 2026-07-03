package com.planmate.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SendTempPasswordRequest(
        @NotBlank String verificationToken
) {
}
