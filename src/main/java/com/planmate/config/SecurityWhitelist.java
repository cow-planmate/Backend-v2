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
            "/api/weather",
            "/api/beta/feedback",
            // 인증 없이 스크랩 가능해야 하는 프로메테우스. 실질적 접근 제한은 network 레벨
            // (management.server.port 분리, 내부망 전용 9091)에서 이뤄진다 — 별도 포트를 써도
            // 이 SecurityFilterChain은 그대로 적용되므로 여기서 명시적으로 열어줘야 한다.
            "/actuator/health",
            "/actuator/prometheus",
            // 토큰 서명 공개키 — 검증자가 인증 없이 받아가야 한다(공개키라 감출 것이 없다)
            "/.well-known/jwks.json",
            // 내부 서비스 전용 API — 인증은 InternalTokenFilter가 별도로 처리한다.
            // 외부에서 오는 요청은 그보다 앞서 게이트웨이가 404로 끊는다
            // (Gateway InternalPathBlockFilter + nginx-gateway.conf). 여기가 열려 있는 것은
            // 도커 네트워크 안의 서비스 간 호출만을 위한 것이다.
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
