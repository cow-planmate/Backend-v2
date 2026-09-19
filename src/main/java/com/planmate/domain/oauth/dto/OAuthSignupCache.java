package com.planmate.domain.oauth.dto;

import com.planmate.domain.user.enums.UserProvider;

public record OAuthSignupCache(
        UserProvider provider,   // 원본은 String, v2는 User 엔티티와 동일한 enum 타입으로 통일
        String providerId,
        String email,
        String nickname,
        // 이미 암호화된 값만 담는다 — 원문을 담으면 Redis에 평문이 최대 TTL(15분)간 노출된다.
        // (04_기능/OAuth/C_SNS_연동해제.md 2-2절)
        String refreshToken
) {}
