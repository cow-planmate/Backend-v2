package com.planmate.domain.auth.dto;

public record EmailVerificationConfirmResponse(
        String verificationToken
) {
}
