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
            "/api/place",
            "/api/place/search",
            "/api/place/text-search",
            "/api/place/text-search/next",
            "/api/place/*", // 장소 상세 GET /api/place/{contentId} — 개인화 없음(05_장소상세_API_설계.md §12)
            "/api/weather",
            "/api/beta/feedback",
            // 인증 없이 스크랩 가능해야 하는 프로메테우스. 실질적 접근 제한은 network 레벨
            // (management.server.port 분리, 내부망 전용 9091)에서 이뤄진다 — 별도 포트를 써도
            // 이 SecurityFilterChain은 그대로 적용되므로 여기서 명시적으로 열어줘야 한다.
            "/actuator/health",
            "/actuator/prometheus",
            // 토큰 서명 공개키 — 검증자가 인증 없이 받아가야 한다(공개키라 감출 것이 없다)
            "/.well-known/jwks.json",
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
