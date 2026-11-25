package com.planmate.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NicknameVerificationRequest(
        @NotBlank @Size(min = 2, max = 20) String nickname
) {
}
