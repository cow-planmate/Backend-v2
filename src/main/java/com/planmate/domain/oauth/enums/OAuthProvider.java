package com.planmate.domain.oauth.enums;

import com.planmate.common.exception.domain.UnsupportedOAuthProviderException;
import com.planmate.domain.user.enums.UserProvider;

import java.util.Arrays;

public enum OAuthProvider {
    KAKAO, GOOGLE, NAVER;

    public static OAuthProvider fromPath(String provider) {
        return Arrays.stream(values())
                .filter(p -> p.name().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(UnsupportedOAuthProviderException::new);
    }

    public UserProvider toUserProvider() {
        return UserProvider.valueOf(this.name());
    }
}
