package com.planmate.domain.auth.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record LoginResponse(
        String accessToken,
        String refreshToken,
        UUID userId,
        String nickname,
        String email,
        String role
) {
}
