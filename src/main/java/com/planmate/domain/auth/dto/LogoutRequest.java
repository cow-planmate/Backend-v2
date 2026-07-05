package com.planmate.domain.auth.dto;

public record LogoutRequest(
        String refreshToken
) {
}
