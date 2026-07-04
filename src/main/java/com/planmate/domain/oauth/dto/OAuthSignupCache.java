package com.planmate.domain.oauth.dto;

import com.planmate.domain.user.enums.UserProvider;

public record OAuthSignupCache(
        UserProvider provider,   // 원본은 String, v2는 User 엔티티와 동일한 enum 타입으로 통일
        String providerId,
        String email,
        String nickname
) {}
