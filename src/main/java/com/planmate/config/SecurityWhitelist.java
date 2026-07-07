package com.planmate.config;

import org.springframework.util.AntPathMatcher;

import java.util.List;

public final class SecurityWhitelist {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    public static final List<String> PATHS = List.of(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/token",
            "/api/auth/register",
            "/api/auth/register/nickname/verify",
            "/api/auth/email/**",
            "/api/auth/password/email",
            "/api/oauth/**",
            "/api/plan/*/complete",
            "/api/destination",
            "/api/beta/feedback",
            "/actuator/health"
    );

    private SecurityWhitelist() {
    }

    public static boolean isWhitelisted(String uri) {
        return PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, uri));
    }
}
