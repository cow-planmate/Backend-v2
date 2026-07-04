package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterFcmTokenRequest(
        @NotBlank String fcmToken
) {
}
