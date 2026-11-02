package com.planmate.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtTokenProvider#validate(String)} 의 계약을 고정한다.
 *
 * <p>이 메서드는 {@code AuthenticationTokenResolver} 구현이고, SharedSync 의
 * {@code JwtHandshakeInterceptor} 가 WebSocket 핸드셰이크에서 부르는 <b>유일한</b> 검증
 * 지점이다. HTTP 필터들과 달리 여기서 {@code typ} 을 안 보면 refresh 토큰으로 협업편집
 * 세션이 열린다 — 그 회귀를 막는 것이 이 테스트의 목적이다.
 */
class JwtTokenProviderTest {


    private static JwtTokenProvider provider() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        JwtSigningKeys keys = new JwtSigningKeys();
        ReflectionTestUtils.setField(keys, "privateKeyPem", pem);
        ReflectionTestUtils.invokeMethod(keys, "init");

        JwtTokenProvider tokenProvider = new JwtTokenProvider(keys);
        ReflectionTestUtils.setField(tokenProvider, "accessTokenExpirySeconds", 900L);
        ReflectionTestUtils.setField(tokenProvider, "refreshTokenExpirySeconds", 604800L);
        return tokenProvider;
    }

    @Test
    @DisplayName("access 토큰은 WebSocket 핸드셰이크를 통과한다")
    void acceptsAccessToken() throws Exception {
        JwtTokenProvider provider = provider();
        UUID userId = UUID.randomUUID();

        String accessToken = provider.createAccessToken(userId, "USER");

        assertThat(provider.validate(accessToken)).isTrue();
        assertThat(provider.extractPrincipalId(accessToken)).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("refresh 토큰으로는 WebSocket 핸드셰이크를 통과하지 못한다")
    void rejectsRefreshToken() throws Exception {
        JwtTokenProvider provider = provider();

        String refreshToken = provider.createRefreshToken(UUID.randomUUID());

        // 서명도 유효하고 만료도 안 됐다 — 그래서 서명만 보는 구현에서는 통과했다.
        assertThat(provider.validateToken(refreshToken)).isTrue();
        // 그러나 이 토큰으로 협업편집 세션을 열 수는 없어야 한다.
        assertThat(provider.validate(refreshToken)).isFalse();
    }

    @Test
    @DisplayName("이메일 인증 토큰처럼 typ 이 없는 토큰도 통과하지 못한다")
    void rejectsTokenWithoutType() throws Exception {
        JwtTokenProvider provider = provider();

        String verificationToken = provider.createEmailVerificationToken(
                "someone@example.com", com.planmate.domain.auth.enums.EmailVerificationPurpose.SIGN_UP);

        assertThat(provider.validate(verificationToken)).isFalse();
    }

    @Test
    @DisplayName("서명이 깨진 토큰은 거부한다")
    void rejectsTamperedToken() throws Exception {
        JwtTokenProvider provider = provider();

        String accessToken = provider.createAccessToken(UUID.randomUUID(), "USER");
        String tampered = accessToken.substring(0, accessToken.length() - 2) + "xy";

        assertThat(provider.validate(tampered)).isFalse();
    }
}
