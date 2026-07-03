package com.planmate.config;

import org.springframework.util.AntPathMatcher;

import java.util.List;

public final class SecurityWhitelist {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    public static final List<String> PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/email/**",
            "/api/auth/password/email",
            "/api/auth/refresh",
            "/api/oauth/**",
            "/api/plan/share/**",
            "/actuator/health"
    );

    private SecurityWhitelist() {
    }

    public static boolean isWhitelisted(String uri) {
        return PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, uri));
    }
}
