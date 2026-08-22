package com.planmate.security;

import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.TokenExpiredException;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.sharedsync.shared.auth.AuthenticationTokenResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider implements AuthenticationTokenResolver {

    @Value("${jwt.access-token-expiry-seconds}")
    private long accessTokenExpirySeconds;

    @Value("${jwt.refresh-token-expiry-seconds}")
    private long refreshTokenExpirySeconds;

    private static final long EMAIL_VERIFICATION_TOKEN_EXPIRY_SECONDS = 600; // 10분

    private final JwtSigningKeys signingKeys;

    /**
     * 검증 시 헤더의 alg 를 보고 키를 고른다. <b>RS 계열만 받는다.</b>
     *
     * <p>예전에는 여기에 HS 분기가 있었다. RS256 전환 시점에 이미 발급된 대칭키 토큰(가장 긴
     * refresh 가 7일)을 위한 유예였는데, 그 유예가 끝난 뒤에도 남아 있었다 —
     * {@code JWT_SECRET} 을 아는 누구나 임의의 {@code sub}/{@code role} 로 토큰을 만들 수
     * 있었다는 뜻이고, 서명 능력을 발급자에게 가두려던 전환이 무의미해진 상태였다.
     * <b>다시 열지 말 것.</b>
     *
     * <p>alg 를 토큰이 정하게 두는 것 자체는 위험하지만(alg=none, RS↔HS 혼동 공격), 여기서는
     * RS 일 때만 공개키를 돌려주고 나머지는 전부 거부하므로 그 경로가 없다.
     */
    private Key resolveVerificationKey(io.jsonwebtoken.Header header) {
        String alg = header.getAlgorithm();
        // alg=none 과 HS 계열을 포함해 RS 가 아닌 것은 전부 거부한다.
        //
        // 여기서는 도메인 예외(InvalidTokenException)가 아니라 jjwt 예외를 던져야 한다.
        // keyLocator 에서 던진 것은 파서를 그대로 통과해 호출부로 올라가는데,
        // validateToken() 과 parseClaims() 는 JwtException 만 잡는다 — 도메인 예외를 던지면
        // validateToken() 이 false 대신 예외를 뱉어 401 이어야 할 응답이 500 이 된다.
        if (alg == null || !alg.startsWith("RS")) {
            throw new UnsupportedJwtException("지원하지 않는 서명 알고리즘: " + alg);
        }
        return signingKeys.getPublicKey();
    }

    /**
     * 서명에는 kid 를 함께 넣는다. 검증자가 JWKS 에서 어떤 키로 확인할지 고르는 근거이며,
     * 키를 교체할 때 두 키를 동시에 게시하고 넘어갈 수 있게 한다.
     */
    private io.jsonwebtoken.JwtBuilder withSigning(io.jsonwebtoken.JwtBuilder builder) {
        builder.header().keyId(signingKeys.getKeyId()).and();
        return builder.signWith(signingKeys.getPrivateKey());
    }

    public String createAccessToken(UUID userId, String role) {
        return createToken(userId, role, "access", accessTokenExpirySeconds);
    }

    public String createRefreshToken(UUID userId) {
        return createToken(userId, null, "refresh", refreshTokenExpirySeconds);
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public Duration getRefreshTokenExpiry() {
        return Duration.ofSeconds(refreshTokenExpirySeconds);
    }

    public String createEmailVerificationToken(String email, EmailVerificationPurpose purpose) {
        Instant now = Instant.now();
        return withSigning(Jwts.builder()
                .subject(email)
                .claim("purpose", purpose.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(EMAIL_VERIFICATION_TOKEN_EXPIRY_SECONDS))))
                .compact();
    }

    public String extractVerifiedEmail(String token, EmailVerificationPurpose expectedPurpose) {
        Claims claims = parseClaims(token);
        String purpose = claims.get("purpose", String.class);
        if (purpose == null || !purpose.equals(expectedPurpose.name())) {
            throw new InvalidTokenException();
        }
        return claims.getSubject();
    }

    public boolean isAccessToken(String token) {
        return "access".equals(parseClaims(token).get("typ", String.class));
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().keyLocator(this::resolveVerificationKey).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean validate(String token) {
        return validateToken(token);
    }

    @Override
    public String extractPrincipalId(String token) {
        return extractUserId(token).toString();
    }

    private String createToken(UUID userId, String role, String type, long expirySeconds) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("typ", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)));
        if (role != null) {
            builder.claim("role", role);
        }
        return withSigning(builder).compact();
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .keyLocator(this::resolveVerificationKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }
}
