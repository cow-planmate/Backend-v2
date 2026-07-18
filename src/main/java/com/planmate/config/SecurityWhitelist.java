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
            "/actuator/health",
            // 내부 서비스 전용 API — 인증은 InternalTokenFilter가 별도로 처리한다
            "/api/internal/**",
            // SharedSync WebSocket 핸드셰이크(SockJS) — 실제 인증은 SharedSync 자체
            // JwtHandshakeInterceptor/WsAuthChannelInterceptor가 별도로 처리한다
            "/ws/**"
    );

    private SecurityWhitelist() {
    }

    public static boolean isWhitelisted(String uri) {
        return PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, uri));
    }
}
