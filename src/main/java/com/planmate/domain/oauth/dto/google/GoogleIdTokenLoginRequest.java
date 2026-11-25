package com.planmate.domain.oauth.dto.google;

import jakarta.validation.constraints.NotBlank;

/** 앱(Android Credential Manager)이 구글에서 받은 ID 토큰 원문 */
public record GoogleIdTokenLoginRequest(
        @NotBlank String idToken
) {
}
